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
import ru.yandex.market_app.mapper.MarketMapper;
import ru.yandex.market_app.model.Basket;
import ru.yandex.market_app.payment.PaymentGateway;
import ru.yandex.market_app.payment.PaymentReceipt;
import ru.yandex.market_app.payment.PaymentServiceUnavailableException;
import ru.yandex.market_app.repository.BasketRepository;
import ru.yandex.market_app.repository.OrderRepository;
import ru.yandex.market_app.repository.ProductRepository;
import ru.yandex.market_app.service.impl.OrderServiceImpl;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderServiceImplTest {

    private static final long BASKET_ID = 7L;
    private static final BigDecimal TOTAL = new BigDecimal("125.50");
    private static final UUID REQUEST_ID = UUID.nameUUIDFromBytes(
        ("market-basket:" + BASKET_ID).getBytes(StandardCharsets.UTF_8)
    );

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
            transactionalOperator
        );

        when(transactionalOperator.transactional(ArgumentMatchers.<Mono<Long>>any()))
            .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void shouldRecoverCheckoutWithSamePaymentRequestAfterLocalFailure() {
        Basket basket = Basket.builder()
            .id(BASKET_ID)
            .status(Basket.Status.ACTIVE)
            .build();
        var receipt = new PaymentReceipt(REQUEST_ID, TOTAL, new BigDecimal("874.50"));

        when(basketRepository.findActiveBasket()).thenReturn(Mono.just(basket));
        when(basketRepository.findActiveBasketByIdForUpdate(BASKET_ID))
            .thenReturn(Mono.just(basket));
        when(basketRepository.calculateBasketTotal(BASKET_ID)).thenReturn(Mono.just(TOTAL));
        when(orderRepository.create(BASKET_ID, TOTAL))
            .thenReturn(Mono.just(10L))
            .thenReturn(Mono.just(11L));
        when(orderRepository.createItemsSnapshot(any(Long.class), eq(BASKET_ID)))
            .thenReturn(Mono.just(1L));
        when(paymentGateway.pay(REQUEST_ID, TOTAL)).thenReturn(Mono.just(receipt));
        when(basketRepository.closeBasket(BASKET_ID))
            .thenReturn(Mono.just(false))
            .thenReturn(Mono.just(true));
        when(orderRepository.findIdByBasketId(BASKET_ID)).thenReturn(Mono.empty());

        StepVerifier.create(orderService.completeOrder())
            .expectNext(11L)
            .verifyComplete();

        verify(paymentGateway, times(2)).pay(REQUEST_ID, TOTAL);
        verify(transactionalOperator, times(2))
            .transactional(ArgumentMatchers.<Mono<Long>>any());
    }

    @Test
    void shouldConfirmAmbiguousPaymentWithOneIdempotentRetry() {
        Basket basket = Basket.builder()
            .id(BASKET_ID)
            .status(Basket.Status.ACTIVE)
            .build();
        var receipt = new PaymentReceipt(REQUEST_ID, TOTAL, new BigDecimal("874.50"));

        when(basketRepository.findActiveBasket()).thenReturn(Mono.just(basket));
        when(basketRepository.findActiveBasketByIdForUpdate(BASKET_ID))
            .thenReturn(Mono.just(basket));
        when(basketRepository.calculateBasketTotal(BASKET_ID)).thenReturn(Mono.just(TOTAL));
        when(orderRepository.create(BASKET_ID, TOTAL)).thenReturn(Mono.just(10L));
        when(orderRepository.createItemsSnapshot(10L, BASKET_ID)).thenReturn(Mono.just(1L));
        when(paymentGateway.pay(REQUEST_ID, TOTAL))
            .thenReturn(Mono.error(new PaymentServiceUnavailableException("response timeout")))
            .thenReturn(Mono.just(receipt));
        when(basketRepository.closeBasket(BASKET_ID)).thenReturn(Mono.just(true));

        StepVerifier.create(orderService.completeOrder())
            .expectNext(10L)
            .verifyComplete();

        verify(paymentGateway, times(2)).pay(REQUEST_ID, TOTAL);
        verify(transactionalOperator)
            .transactional(ArgumentMatchers.<Mono<Long>>any());
    }
}
