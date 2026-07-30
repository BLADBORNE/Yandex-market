package ru.yandex.market_app.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.yandex.market_app.configuration.PaymentClientProperties;
import ru.yandex.market_app.mapper.MarketMapper;
import ru.yandex.market_app.model.Basket;
import ru.yandex.market_app.model.Order;
import ru.yandex.market_app.payment.InsufficientFundsException;
import ru.yandex.market_app.payment.PaymentGateway;
import ru.yandex.market_app.payment.PaymentReceipt;
import ru.yandex.market_app.payment.PaymentRejectedException;
import ru.yandex.market_app.payment.PaymentServiceUnavailableException;
import ru.yandex.market_app.repository.BasketRepository;
import ru.yandex.market_app.repository.OrderRepository;
import ru.yandex.market_app.repository.OrderRepository.PaymentClaim;
import ru.yandex.market_app.repository.ProductRepository;
import ru.yandex.market_app.service.impl.OrderServiceImpl;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderServiceImplTest {

    private static final long ORDER_ID = 10L;
    private static final long BASKET_ID = 7L;
    private static final long USER_ID = 3L;
    private static final UUID PAYMENT_ACCOUNT_ID =
        UUID.fromString("85a65fde-0492-4dcf-b1f4-dbc8f901ae72");
    private static final UUID REQUEST_ID =
        UUID.fromString("d989189e-0e6d-4e3d-9792-673629702050");
    private static final Duration RETRY_DELAY = Duration.ofMillis(100);
    private static final BigDecimal TOTAL = new BigDecimal("125.50");

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private BasketRepository basketRepository;

    @Mock
    private ProductRepository productRepository;

    @Mock
    private MarketMapper marketMapper;

    @Mock
    private PaymentGateway paymentGateway;

    @Mock
    private TransactionalOperator transactionalOperator;

    private OrderServiceImpl orderService;

    @BeforeEach
    void setUp() {
        orderService = new OrderServiceImpl(
            orderRepository,
            basketRepository,
            productRepository,
            marketMapper,
            paymentGateway,
            new PaymentClientProperties(
                URI.create("http://localhost:8081"),
                Duration.ofSeconds(1),
                Duration.ofSeconds(2),
                RETRY_DELAY
            ),
            transactionalOperator
        );

        lenient().when(transactionalOperator.transactional(ArgumentMatchers.<Mono<?>>any()))
            .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void shouldPreparePayAndFinalizeCheckoutInShortTransactions() {
        Basket activeBasket = basket(Basket.Status.ACTIVE);
        Basket checkoutBasket = basket(Basket.Status.CHECKOUT);
        Order pendingOrder = pendingOrder();
        PaymentReceipt receipt = receipt();

        when(orderRepository.findPendingByUserId(USER_ID)).thenReturn(Mono.empty());
        when(basketRepository.findActiveBasketForUpdate(USER_ID)).thenReturn(Mono.just(activeBasket));
        when(orderRepository.createPending(
            eq(BASKET_ID),
            eq(USER_ID),
            eq(BigDecimal.ZERO),
            eq(PAYMENT_ACCOUNT_ID),
            any(UUID.class)
        )).thenReturn(Mono.just(pendingOrder));
        when(orderRepository.createItemsSnapshot(ORDER_ID, BASKET_ID)).thenReturn(Mono.just(1L));
        when(orderRepository.countItemsSnapshot(ORDER_ID)).thenReturn(Mono.just(1L));
        when(orderRepository.updatePendingSumFromSnapshot(ORDER_ID, USER_ID))
            .thenReturn(Mono.just(TOTAL));
        when(basketRepository.markCheckout(USER_ID, BASKET_ID)).thenReturn(Mono.just(true));
        when(orderRepository.claimPayment(
            eq(ORDER_ID),
            eq(USER_ID),
            any(UUID.class),
            anyLong()
        )).thenReturn(Mono.just(new PaymentClaim(false)));
        when(paymentGateway.pay(PAYMENT_ACCOUNT_ID, REQUEST_ID, TOTAL)).thenReturn(Mono.just(receipt));
        when(basketRepository.findCheckoutBasketByIdForUpdate(USER_ID, BASKET_ID))
            .thenReturn(Mono.just(checkoutBasket));
        when(orderRepository.markCompleted(eq(ORDER_ID), eq(USER_ID), any(UUID.class)))
            .thenReturn(Mono.just(ORDER_ID));
        when(basketRepository.closeCheckout(USER_ID, BASKET_ID)).thenReturn(Mono.just(true));

        StepVerifier.create(orderService.completeOrder(USER_ID, PAYMENT_ACCOUNT_ID))
            .expectNext(ORDER_ID)
            .verifyComplete();

        verify(paymentGateway).pay(PAYMENT_ACCOUNT_ID, REQUEST_ID, TOTAL);
        verify(transactionalOperator, times(3))
            .transactional(ArgumentMatchers.<Mono<?>>any());
    }

    @Test
    void shouldConfirmAmbiguousPaymentWithDelayedIdempotentRetry() {
        Order pendingOrder = pendingOrder();
        PaymentReceipt receipt = receipt();

        when(orderRepository.findPendingByUserId(USER_ID)).thenReturn(Mono.just(pendingOrder));
        when(orderRepository.claimPayment(
            eq(ORDER_ID),
            eq(USER_ID),
            any(UUID.class),
            anyLong()
        )).thenReturn(Mono.just(new PaymentClaim(false)));
        when(paymentGateway.pay(PAYMENT_ACCOUNT_ID, REQUEST_ID, TOTAL))
            .thenReturn(Mono.error(new PaymentServiceUnavailableException("response timeout")))
            .thenReturn(Mono.just(receipt));
        when(basketRepository.findCheckoutBasketByIdForUpdate(USER_ID, BASKET_ID))
            .thenReturn(Mono.just(basket(Basket.Status.CHECKOUT)));
        when(orderRepository.markCompleted(eq(ORDER_ID), eq(USER_ID), any(UUID.class)))
            .thenReturn(Mono.just(ORDER_ID));
        when(basketRepository.closeCheckout(USER_ID, BASKET_ID)).thenReturn(Mono.just(true));

        StepVerifier.withVirtualTime(() -> orderService.completeOrder(USER_ID, PAYMENT_ACCOUNT_ID))
            .expectSubscription()
            .then(() -> verify(paymentGateway).pay(PAYMENT_ACCOUNT_ID, REQUEST_ID, TOTAL))
            .expectNoEvent(RETRY_DELAY.minusNanos(1))
            .thenAwait(Duration.ofNanos(1))
            .then(() -> verify(paymentGateway, times(2))
                .pay(PAYMENT_ACCOUNT_ID, REQUEST_ID, TOTAL))
            .expectNext(ORDER_ID)
            .verifyComplete();

        verify(paymentGateway, times(2)).pay(PAYMENT_ACCOUNT_ID, REQUEST_ID, TOTAL);
    }

    @Test
    void shouldKeepPendingCheckoutWhenBothPaymentResponsesAreUnavailable() {
        Order pendingOrder = pendingOrder();
        var paymentError = new PaymentServiceUnavailableException("response timeout");

        when(orderRepository.findPendingByUserId(USER_ID)).thenReturn(Mono.just(pendingOrder));
        when(orderRepository.claimPayment(
            eq(ORDER_ID),
            eq(USER_ID),
            any(UUID.class),
            anyLong()
        )).thenReturn(Mono.just(new PaymentClaim(false)));
        when(paymentGateway.pay(PAYMENT_ACCOUNT_ID, REQUEST_ID, TOTAL))
            .thenReturn(Mono.error(paymentError));
        when(orderRepository.releasePaymentClaim(
            eq(ORDER_ID),
            eq(USER_ID),
            any(UUID.class)
        )).thenReturn(Mono.just(true));

        StepVerifier.withVirtualTime(() -> orderService.completeOrder(USER_ID, PAYMENT_ACCOUNT_ID))
            .expectSubscription()
            .thenAwait(RETRY_DELAY)
            .expectError(PaymentServiceUnavailableException.class)
            .verify();

        verify(paymentGateway, times(2)).pay(PAYMENT_ACCOUNT_ID, REQUEST_ID, TOTAL);
        verify(orderRepository, never()).deletePending(
            any(Long.class),
            any(Long.class),
            any(UUID.class)
        );
        verify(orderRepository, never()).markCompleted(
            any(Long.class),
            any(Long.class),
            any(UUID.class)
        );
        verify(basketRepository, never()).restoreActive(any(Long.class), any(Long.class));
        verify(basketRepository, never()).closeCheckout(any(Long.class), any(Long.class));
    }

    @Test
    void shouldKeepPendingCheckoutWhenRetryIsDefinitivelyRejectedAfterTimeout() {
        Order pendingOrder = pendingOrder();

        when(orderRepository.findPendingByUserId(USER_ID)).thenReturn(Mono.just(pendingOrder));
        when(orderRepository.claimPayment(
            eq(ORDER_ID),
            eq(USER_ID),
            any(UUID.class),
            anyLong()
        )).thenReturn(Mono.just(new PaymentClaim(false)));
        when(paymentGateway.pay(PAYMENT_ACCOUNT_ID, REQUEST_ID, TOTAL))
            .thenReturn(Mono.error(new PaymentServiceUnavailableException("response timeout")))
            .thenReturn(Mono.error(new PaymentRejectedException("stale token")));
        when(orderRepository.releasePaymentClaim(
            eq(ORDER_ID),
            eq(USER_ID),
            any(UUID.class)
        )).thenReturn(Mono.just(true));

        StepVerifier.withVirtualTime(() -> orderService.completeOrder(USER_ID, PAYMENT_ACCOUNT_ID))
            .expectSubscription()
            .thenAwait(RETRY_DELAY)
            .expectError(PaymentServiceUnavailableException.class)
            .verify();

        verify(orderRepository, never()).deletePending(
            any(Long.class),
            any(Long.class),
            any(UUID.class)
        );
        verify(basketRepository, never()).restoreActive(any(Long.class), any(Long.class));
    }

    @Test
    void shouldCancelCheckoutWhenRetryReportsInsufficientFundsAfterTimeout() {
        Order pendingOrder = pendingOrder();
        var insufficientFunds = new InsufficientFundsException(
            BigDecimal.ZERO,
            TOTAL
        );

        when(orderRepository.findPendingByUserId(USER_ID)).thenReturn(Mono.just(pendingOrder));
        when(orderRepository.claimPayment(
            eq(ORDER_ID),
            eq(USER_ID),
            any(UUID.class),
            anyLong()
        )).thenReturn(Mono.just(new PaymentClaim(false)));
        when(paymentGateway.pay(PAYMENT_ACCOUNT_ID, REQUEST_ID, TOTAL))
            .thenReturn(Mono.error(new PaymentServiceUnavailableException("response timeout")))
            .thenReturn(Mono.error(insufficientFunds));
        when(basketRepository.findCheckoutBasketByIdForUpdate(USER_ID, BASKET_ID))
            .thenReturn(Mono.just(basket(Basket.Status.CHECKOUT)));
        when(orderRepository.deletePending(
            eq(ORDER_ID),
            eq(USER_ID),
            any(UUID.class)
        )).thenReturn(Mono.just(true));
        when(basketRepository.restoreActive(USER_ID, BASKET_ID)).thenReturn(Mono.just(true));

        StepVerifier.withVirtualTime(() -> orderService.completeOrder(USER_ID, PAYMENT_ACCOUNT_ID))
            .expectSubscription()
            .thenAwait(RETRY_DELAY)
            .expectErrorMatches(error -> error == insufficientFunds)
            .verify();

        verify(paymentGateway, times(2)).pay(PAYMENT_ACCOUNT_ID, REQUEST_ID, TOTAL);
        verify(orderRepository).deletePending(
            eq(ORDER_ID),
            eq(USER_ID),
            any(UUID.class)
        );
        verify(basketRepository).restoreActive(USER_ID, BASKET_ID);
        verify(orderRepository, never()).releasePaymentClaim(
            any(Long.class),
            any(Long.class),
            any(UUID.class)
        );
    }

    @Test
    void shouldCancelPendingCheckoutWithoutRetryAfterDefinitiveRejection() {
        Order pendingOrder = pendingOrder();
        var paymentError = new PaymentRejectedException("forbidden");

        when(orderRepository.findPendingByUserId(USER_ID)).thenReturn(Mono.just(pendingOrder));
        when(orderRepository.claimPayment(
            eq(ORDER_ID),
            eq(USER_ID),
            any(UUID.class),
            anyLong()
        )).thenReturn(Mono.just(new PaymentClaim(false)));
        when(paymentGateway.pay(PAYMENT_ACCOUNT_ID, REQUEST_ID, TOTAL))
            .thenReturn(Mono.error(paymentError));
        when(basketRepository.findCheckoutBasketByIdForUpdate(USER_ID, BASKET_ID))
            .thenReturn(Mono.just(basket(Basket.Status.CHECKOUT)));
        when(orderRepository.deletePending(
            eq(ORDER_ID),
            eq(USER_ID),
            any(UUID.class)
        )).thenReturn(Mono.just(true));
        when(basketRepository.restoreActive(USER_ID, BASKET_ID)).thenReturn(Mono.just(true));

        StepVerifier.create(orderService.completeOrder(USER_ID, PAYMENT_ACCOUNT_ID))
            .expectError(PaymentRejectedException.class)
            .verify();

        verify(paymentGateway).pay(PAYMENT_ACCOUNT_ID, REQUEST_ID, TOTAL);
        verify(orderRepository).deletePending(
            eq(ORDER_ID),
            eq(USER_ID),
            any(UUID.class)
        );
        verify(basketRepository).restoreActive(USER_ID, BASKET_ID);
        verify(orderRepository, never()).markCompleted(
            any(Long.class),
            any(Long.class),
            any(UUID.class)
        );
    }

    @Test
    void shouldPreservePreviouslyAmbiguousCheckoutAfterDefinitiveRejection() {
        Order stalePendingOrder = pendingOrder();
        var paymentError = new PaymentRejectedException("conflict");

        when(orderRepository.findPendingByUserId(USER_ID)).thenReturn(Mono.just(stalePendingOrder));
        when(orderRepository.claimPayment(
            eq(ORDER_ID),
            eq(USER_ID),
            any(UUID.class),
            anyLong()
        )).thenReturn(Mono.just(new PaymentClaim(true)));
        when(paymentGateway.pay(PAYMENT_ACCOUNT_ID, REQUEST_ID, TOTAL))
            .thenReturn(Mono.error(paymentError));
        when(orderRepository.releasePaymentClaim(
            eq(ORDER_ID),
            eq(USER_ID),
            any(UUID.class)
        )).thenReturn(Mono.just(true));

        StepVerifier.create(orderService.completeOrder(USER_ID, PAYMENT_ACCOUNT_ID))
            .expectErrorMatches(error ->
                error instanceof PaymentServiceUnavailableException
                    && error.getCause() == paymentError)
            .verify();

        verify(orderRepository, never()).deletePending(
            any(Long.class),
            any(Long.class),
            any(UUID.class)
        );
        verify(basketRepository, never()).restoreActive(any(Long.class), any(Long.class));
    }

    @Test
    void shouldCancelPreviouslyAmbiguousCheckoutWhenPaymentReportsInsufficientFunds() {
        Order stalePendingOrder = pendingOrder();
        var insufficientFunds = new InsufficientFundsException(
            BigDecimal.ZERO,
            TOTAL
        );

        when(orderRepository.findPendingByUserId(USER_ID)).thenReturn(Mono.just(stalePendingOrder));
        when(orderRepository.claimPayment(
            eq(ORDER_ID),
            eq(USER_ID),
            any(UUID.class),
            anyLong()
        )).thenReturn(Mono.just(new PaymentClaim(true)));
        when(paymentGateway.pay(PAYMENT_ACCOUNT_ID, REQUEST_ID, TOTAL))
            .thenReturn(Mono.error(insufficientFunds));
        when(basketRepository.findCheckoutBasketByIdForUpdate(USER_ID, BASKET_ID))
            .thenReturn(Mono.just(basket(Basket.Status.CHECKOUT)));
        when(orderRepository.deletePending(
            eq(ORDER_ID),
            eq(USER_ID),
            any(UUID.class)
        )).thenReturn(Mono.just(true));
        when(basketRepository.restoreActive(USER_ID, BASKET_ID)).thenReturn(Mono.just(true));

        StepVerifier.create(orderService.completeOrder(USER_ID, PAYMENT_ACCOUNT_ID))
            .expectErrorMatches(error -> error == insufficientFunds)
            .verify();

        verify(orderRepository).deletePending(
            eq(ORDER_ID),
            eq(USER_ID),
            any(UUID.class)
        );
        verify(basketRepository).restoreActive(USER_ID, BASKET_ID);
        verify(orderRepository, never()).releasePaymentClaim(
            any(Long.class),
            any(Long.class),
            any(UUID.class)
        );
    }

    @Test
    void shouldReturnCompletedOrderAfterLostFinalizationAcknowledgement() {
        Order pendingOrder = pendingOrder();

        when(orderRepository.findPendingByUserId(USER_ID)).thenReturn(Mono.just(pendingOrder));
        when(orderRepository.claimPayment(
            eq(ORDER_ID),
            eq(USER_ID),
            any(UUID.class),
            anyLong()
        )).thenReturn(Mono.just(new PaymentClaim(false)));
        when(paymentGateway.pay(PAYMENT_ACCOUNT_ID, REQUEST_ID, TOTAL))
            .thenReturn(Mono.just(receipt()));
        when(basketRepository.findCheckoutBasketByIdForUpdate(USER_ID, BASKET_ID))
            .thenReturn(Mono.just(basket(Basket.Status.CHECKOUT)));
        when(orderRepository.markCompleted(eq(ORDER_ID), eq(USER_ID), any(UUID.class)))
            .thenReturn(Mono.error(new IllegalStateException("commit acknowledgement lost")));
        when(orderRepository.findCompletedId(ORDER_ID, USER_ID))
            .thenReturn(Mono.just(ORDER_ID));

        StepVerifier.create(orderService.completeOrder(USER_ID, PAYMENT_ACCOUNT_ID))
            .expectNext(ORDER_ID)
            .verifyComplete();

        verify(orderRepository, never()).releasePaymentClaim(
            any(Long.class),
            any(Long.class),
            any(UUID.class)
        );
    }

    private Basket basket(Basket.Status status) {
        return Basket.builder()
            .id(BASKET_ID)
            .userId(USER_ID)
            .status(status)
            .build();
    }

    private Order pendingOrder() {
        return Order.builder()
            .id(ORDER_ID)
            .basketId(BASKET_ID)
            .userId(USER_ID)
            .sum(TOTAL)
            .status(Order.Status.PENDING)
            .paymentAccountId(PAYMENT_ACCOUNT_ID)
            .paymentRequestId(REQUEST_ID)
            .build();
    }

    private PaymentReceipt receipt() {
        return new PaymentReceipt(REQUEST_ID, TOTAL, new BigDecimal("874.50"));
    }
}
