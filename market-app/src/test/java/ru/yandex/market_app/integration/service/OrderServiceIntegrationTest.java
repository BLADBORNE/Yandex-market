package ru.yandex.market_app.integration.service;

import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Signal;
import reactor.test.StepVerifier;
import ru.yandex.market_app.integration.ReactiveIntegrationTest;
import ru.yandex.market_app.integration.ReactiveIntegrationTestSupport;
import ru.yandex.market_app.exception.OperationNotSupportedException;
import ru.yandex.market_app.payment.InsufficientFundsException;
import ru.yandex.market_app.payment.PaymentRejectedException;
import ru.yandex.market_app.payment.PaymentServiceUnavailableException;
import ru.yandex.market_app.repository.OrderRepository;
import ru.yandex.market_app.service.BasketService;
import ru.yandex.market_app.service.OrderService;

import java.math.BigDecimal;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static ru.yandex.market_app.model.ProductAction.PLUS;

@ReactiveIntegrationTest
@RequiredArgsConstructor(onConstructor_ = @Autowired)
class OrderServiceIntegrationTest extends ReactiveIntegrationTestSupport {

    private static final BigDecimal FIRST_PRODUCT_PRICE = BigDecimal.valueOf(54999);
    private static final BigDecimal SECOND_PRODUCT_PRICE = BigDecimal.valueOf(34999);

    private final OrderService orderService;
    private final BasketService basketService;
    private final OrderRepository orderRepository;

    @Test
    void shouldReturnEmptyOrderList() {
        StepVerifier.create(resetDatabase().then(orderService.getOrders(ALICE_ID)))
            .assertNext(result -> assertTrue(result.orders().isEmpty()))
            .verifyComplete();
    }

    @Test
    void shouldRejectCheckoutWithoutNonEmptyActiveCart() {
        StepVerifier.create(resetDatabase().then(orderService.completeOrder(ALICE_ID, ALICE_PAYMENT_ACCOUNT)))
            .expectError(NoSuchElementException.class)
            .verify();

        var emptyActiveCart = resetDatabase()
            .then(basketService.changeProductCountFromStartPage(ALICE_ID, 1L, PLUS))
            .then(basketService.changeProductCountFromStartPage(
                ALICE_ID,
                1L,
                ru.yandex.market_app.model.ProductAction.MINUS
            ))
            .then(orderService.completeOrder(ALICE_ID, ALICE_PAYMENT_ACCOUNT));

        StepVerifier.create(emptyActiveCart)
            .expectErrorMatches(error -> error instanceof NoSuchElementException
                && error.getMessage().contains("пуста"))
            .verify();
    }

    @Test
    void shouldCreateOrderSnapshotCloseCartAndReturnOrder() {
        BigDecimal expectedTotal = FIRST_PRODUCT_PRICE.multiply(BigDecimal.TWO).add(SECOND_PRODUCT_PRICE);

        var scenario = resetDatabase()
            .then(basketService.changeProductCountFromStartPage(ALICE_ID, 1L, PLUS))
            .then(basketService.changeProductCountFromStartPage(ALICE_ID, 1L, PLUS))
            .then(basketService.changeProductCountFromStartPage(ALICE_ID, 2L, PLUS))
            .then(orderService.completeOrder(ALICE_ID, ALICE_PAYMENT_ACCOUNT))
            .flatMap(orderId -> orderService.getOrder(ALICE_ID, orderId)
                .doOnNext(order -> {
                    assertEquals(orderId, order.id());
                    assertEquals(2, order.items().size());
                    assertEquals(0, expectedTotal.compareTo(order.totalSum()));
                    assertEquals(2, order.items().stream()
                        .filter(item -> item.id() == 1L)
                        .findFirst()
                        .orElseThrow()
                        .count());
                })
                .then(basketService.getCart(ALICE_ID))
                .doOnNext(cart -> assertTrue(cart.items().isEmpty()))
                .thenReturn(orderId))
            .flatMap(orderId -> orderService.getOrders(ALICE_ID)
                .doOnNext(orders -> {
                    assertEquals(1, orders.orders().size());
                    assertEquals(orderId, orders.orders().getFirst().id());
                })
                .thenReturn(orderId));

        StepVerifier.create(scenario)
            .assertNext(value -> assertNotNull(value))
            .verifyComplete();
    }

    @Test
    void shouldCreateMultipleIndependentOrders() {
        var scenario = resetDatabase()
            .then(basketService.changeProductCountFromStartPage(ALICE_ID, 1L, PLUS))
            .then(orderService.completeOrder(ALICE_ID, ALICE_PAYMENT_ACCOUNT))
            .flatMap(firstId -> basketService.changeProductCountFromStartPage(ALICE_ID, 2L, PLUS)
                .then(orderService.completeOrder(ALICE_ID, ALICE_PAYMENT_ACCOUNT))
                .map(secondId -> {
                    assertNotEquals(firstId, secondId);
                    return secondId;
                }))
            .then(orderService.getOrders(ALICE_ID));

        StepVerifier.create(scenario)
            .assertNext(result -> assertEquals(2, result.orders().size()))
            .verifyComplete();
    }

    @Test
    void shouldKeepOrderItemSnapshotWhenCatalogProductChanges() {
        String originalTitle = "Ноутбук ASUS VivoBook";

        var scenario = resetDatabase()
            .then(basketService.changeProductCountFromStartPage(ALICE_ID, 1L, PLUS))
            .then(orderService.completeOrder(ALICE_ID, ALICE_PAYMENT_ACCOUNT))
            .flatMap(orderId -> updateProduct("Обновлённый товар", BigDecimal.ONE)
                .then(orderService.getOrder(ALICE_ID, orderId)))
            .flatMap(order -> updateProduct(originalTitle, FIRST_PRODUCT_PRICE)
                .thenReturn(order));

        StepVerifier.create(scenario)
            .assertNext(order -> {
                assertEquals(originalTitle, order.items().getFirst().title());
                assertEquals(0, FIRST_PRODUCT_PRICE.compareTo(order.items().getFirst().price()));
                assertEquals(0, FIRST_PRODUCT_PRICE.compareTo(order.totalSum()));
            })
            .verifyComplete();
    }

    @Test
    void shouldAllowOnlyOnePaymentAttemptForTwoConcurrentCheckouts() {
        var scenario = resetDatabase()
            .then(basketService.changeProductCountFromStartPage(ALICE_ID, 1L, PLUS))
            .then(Mono.zip(
                orderService.completeOrder(ALICE_ID, ALICE_PAYMENT_ACCOUNT).materialize(),
                orderService.completeOrder(ALICE_ID, ALICE_PAYMENT_ACCOUNT).materialize()
            ));

        StepVerifier.create(scenario)
            .assertNext(signals -> {
                long successes = java.util.stream.Stream.of(signals.getT1(), signals.getT2())
                    .filter(Signal::hasValue)
                    .count();
                long busyFailures = java.util.stream.Stream.of(signals.getT1(), signals.getT2())
                    .filter(Signal::isOnError)
                    .filter(signal ->
                        signal.getThrowable() instanceof PaymentServiceUnavailableException)
                    .count();

                assertEquals(1, successes);
                assertEquals(1, busyFailures);
                assertEquals(1, testPaymentGateway.requestIds().size());
            })
            .verifyComplete();
    }

    @Test
    void shouldRollbackOrderAndKeepCartWhenPaymentFails() {
        var paymentError = new InsufficientFundsException(
            BigDecimal.ZERO,
            FIRST_PRODUCT_PRICE
        );

        var scenario = resetDatabase()
            .then(basketService.changeProductCountFromStartPage(ALICE_ID, 1L, PLUS))
            .then(Mono.fromRunnable(() -> testPaymentGateway.failPayment(paymentError)))
            .then(orderService.completeOrder(ALICE_ID, ALICE_PAYMENT_ACCOUNT).materialize())
            .flatMap(paymentSignal -> Mono.zip(
                Mono.just(paymentSignal),
                databaseClient.sql("SELECT COUNT(*) AS total FROM market.\"order\"")
                    .map((row, metadata) -> row.get("total", Long.class))
                    .one(),
                basketService.getCart(ALICE_ID)
            ));

        StepVerifier.create(scenario)
            .assertNext(result -> {
                assertTrue(result.getT1().isOnError());
                assertEquals(paymentError, result.getT1().getThrowable());
                assertEquals(0L, result.getT2());
                assertEquals(1, result.getT3().items().size());
                assertEquals(0, FIRST_PRODUCT_PRICE.compareTo(result.getT3().total()));
            })
            .verifyComplete();
    }

    @Test
    void shouldRestoreCartWhenRetryReportsInsufficientFundsAfterTimeout() {
        var unavailable = new PaymentServiceUnavailableException("response timeout");
        var insufficientFunds = new InsufficientFundsException(
            BigDecimal.ZERO,
            FIRST_PRODUCT_PRICE
        );

        var scenario = resetDatabase()
            .then(basketService.changeProductCountFromStartPage(ALICE_ID, 1L, PLUS))
            .then(Mono.fromRunnable(() ->
                testPaymentGateway.failFirstPaymentThen(unavailable, insufficientFunds)))
            .then(orderService.completeOrder(ALICE_ID, ALICE_PAYMENT_ACCOUNT).materialize())
            .flatMap(paymentSignal -> basketService
                .changeProductCountFromStartPage(ALICE_ID, 2L, PLUS)
                .then(Mono.zip(
                    Mono.just(paymentSignal),
                    basketService.getCart(ALICE_ID),
                    orderService.getOrders(ALICE_ID)
                )));

        StepVerifier.create(scenario)
            .assertNext(result -> {
                assertTrue(result.getT1().isOnError());
                assertEquals(insufficientFunds, result.getT1().getThrowable());
                assertEquals(2, result.getT2().items().size());
                assertTrue(result.getT3().orders().isEmpty());
                assertEquals(2, testPaymentGateway.requestIds().size());
                assertEquals(1, Set.copyOf(testPaymentGateway.requestIds()).size());
            })
            .verifyComplete();
    }

    @Test
    void shouldRestoreCartWhenPreviouslyAttemptedPaymentReportsInsufficientFunds() {
        var unavailable = new PaymentServiceUnavailableException("response timeout");
        var insufficientFunds = new InsufficientFundsException(
            BigDecimal.ZERO,
            FIRST_PRODUCT_PRICE
        );

        var scenario = resetDatabase()
            .then(basketService.changeProductCountFromStartPage(ALICE_ID, 1L, PLUS))
            .then(Mono.fromRunnable(() -> testPaymentGateway.failPayment(unavailable)))
            .then(orderService.completeOrder(ALICE_ID, ALICE_PAYMENT_ACCOUNT).materialize())
            .doOnNext(firstAttempt -> {
                assertTrue(firstAttempt.isOnError());
                assertTrue(firstAttempt.getThrowable() instanceof PaymentServiceUnavailableException);
            })
            .then(Mono.fromRunnable(() -> testPaymentGateway.failPayment(insufficientFunds)))
            .then(orderService.completeOrder(ALICE_ID, ALICE_PAYMENT_ACCOUNT).materialize())
            .flatMap(secondAttempt -> basketService
                .changeProductCountFromStartPage(ALICE_ID, 2L, PLUS)
                .then(Mono.zip(
                    Mono.just(secondAttempt),
                    basketService.getCart(ALICE_ID),
                    orderService.getOrders(ALICE_ID)
                )));

        StepVerifier.create(scenario)
            .assertNext(result -> {
                assertTrue(result.getT1().isOnError());
                assertEquals(insufficientFunds, result.getT1().getThrowable());
                assertEquals(2, result.getT2().items().size());
                assertTrue(result.getT3().orders().isEmpty());
                assertEquals(3, testPaymentGateway.requestIds().size());
                assertEquals(1, Set.copyOf(testPaymentGateway.requestIds()).size());
            })
            .verifyComplete();
    }

    @Test
    void shouldCreateNewPaymentKeyAfterDefinitiveRejectionAndCartEdit() {
        var paymentError = new InsufficientFundsException(
            BigDecimal.ZERO,
            FIRST_PRODUCT_PRICE
        );

        var scenario = resetDatabase()
            .then(basketService.changeProductCountFromStartPage(ALICE_ID, 1L, PLUS))
            .then(Mono.fromRunnable(() -> testPaymentGateway.failPayment(paymentError)))
            .then(orderService.completeOrder(ALICE_ID, ALICE_PAYMENT_ACCOUNT).materialize())
            .doOnNext(firstAttempt -> {
                assertTrue(firstAttempt.isOnError());
                assertEquals(paymentError, firstAttempt.getThrowable());
            })
            .then(Mono.fromRunnable(testPaymentGateway::succeedPayments))
            .then(basketService.changeProductCountFromStartPage(ALICE_ID, 2L, PLUS))
            .then(orderService.completeOrder(ALICE_ID, ALICE_PAYMENT_ACCOUNT))
            .flatMap(orderId -> orderService.getOrder(ALICE_ID, orderId));

        StepVerifier.create(scenario)
            .assertNext(order -> {
                assertEquals(2, order.items().size());
                assertEquals(2, testPaymentGateway.requestIds().size());
                assertEquals(2, Set.copyOf(testPaymentGateway.requestIds()).size());
            })
            .verifyComplete();
    }

    @Test
    void shouldCommitPendingCheckoutBeforeCallingPaymentOutsideTransaction() {
        var scenario = resetDatabase()
            .then(basketService.changeProductCountFromStartPage(ALICE_ID, 1L, PLUS))
            .then(Mono.fromRunnable(testPaymentGateway::assertPreparedCheckoutOutsideTransaction))
            .then(orderService.completeOrder(ALICE_ID, ALICE_PAYMENT_ACCOUNT))
            .flatMap(orderId -> orderService.getOrder(ALICE_ID, orderId));

        StepVerifier.create(scenario)
            .assertNext(order ->
                assertEquals(0, FIRST_PRODUCT_PRICE.compareTo(order.totalSum())))
            .verifyComplete();
    }

    @Test
    void shouldFreezeAndResumePendingCheckoutAfterTwoLostPaymentResponses() {
        var unavailable = new PaymentServiceUnavailableException("response timeout");

        var scenario = resetDatabase()
            .then(basketService.changeProductCountFromStartPage(ALICE_ID, 1L, PLUS))
            .then(Mono.fromRunnable(() ->
                testPaymentGateway.loseNextPaymentResponses(2, unavailable)))
            .then(orderService.completeOrder(ALICE_ID, ALICE_PAYMENT_ACCOUNT).materialize())
            .flatMap(firstAttempt -> Mono.zip(
                Mono.just(firstAttempt),
                pendingCheckoutState(),
                basketService.changeProductCountFromStartPage(ALICE_ID, 2L, PLUS).materialize(),
                basketService.getCart(ALICE_ID)
            ))
            .doOnNext(result -> {
                assertTrue(result.getT1().isOnError());
                assertTrue(result.getT1().getThrowable() instanceof PaymentServiceUnavailableException);
                assertEquals("PENDING", result.getT2().orderStatus());
                assertEquals("CHECKOUT", result.getT2().basketStatus());
                assertTrue(result.getT3().isOnError());
                assertTrue(result.getT3().getThrowable() instanceof OperationNotSupportedException);
                assertEquals(1, result.getT4().items().size());
                assertEquals(0, FIRST_PRODUCT_PRICE.compareTo(result.getT4().total()));
            })
            .flatMap(pending -> orderService.completeOrder(ALICE_ID, ALICE_PAYMENT_ACCOUNT)
                .flatMap(orderId -> completedCheckoutState(orderId)
                    .map(completed -> new CheckoutRecoveryResult(
                        pending.getT2(),
                        completed
                    ))));

        StepVerifier.create(scenario)
            .assertNext(result -> {
                assertEquals(result.pending().orderId(), result.completed().orderId());
                assertEquals("COMPLETED", result.completed().orderStatus());
                assertEquals("CLOSED", result.completed().basketStatus());
                assertEquals(3, testPaymentGateway.requestIds().size());
                assertEquals(1, Set.copyOf(testPaymentGateway.requestIds()).size());
                assertEquals(1, testPaymentGateway.debitCount());
                assertEquals(
                    result.pending().paymentRequestId(),
                    testPaymentGateway.requestIds().getFirst()
                );
                assertEquals(
                    Set.of(ALICE_PAYMENT_ACCOUNT),
                    Set.copyOf(testPaymentGateway.accountIds())
                );
            })
            .verifyComplete();
    }

    @Test
    void shouldNotCancelPaidIntentWhenLaterAttemptIsDefinitivelyRejected() {
        var unavailable = new PaymentServiceUnavailableException("response timeout");

        var scenario = resetDatabase()
            .then(basketService.changeProductCountFromStartPage(ALICE_ID, 1L, PLUS))
            .then(Mono.fromRunnable(() ->
                testPaymentGateway.loseNextPaymentResponses(2, unavailable)))
            .then(orderService.completeOrder(ALICE_ID, ALICE_PAYMENT_ACCOUNT).materialize())
            .doOnNext(firstAttempt -> {
                assertTrue(firstAttempt.isOnError());
                assertTrue(firstAttempt.getThrowable() instanceof PaymentServiceUnavailableException);
            })
            .then(Mono.fromRunnable(() ->
                testPaymentGateway.failPayment(new PaymentRejectedException("conflict"))))
            .then(orderService.completeOrder(ALICE_ID, ALICE_PAYMENT_ACCOUNT).materialize())
            .flatMap(secondAttempt -> Mono.zip(
                Mono.just(secondAttempt),
                pendingCheckoutState()
            ))
            .doOnNext(result -> {
                assertTrue(result.getT1().isOnError());
                assertTrue(result.getT1().getThrowable() instanceof PaymentServiceUnavailableException);
                assertTrue(result.getT2().paymentAttempted());
                assertEquals("PENDING", result.getT2().orderStatus());
                assertEquals("CHECKOUT", result.getT2().basketStatus());
                assertEquals(1, testPaymentGateway.debitCount());
                assertEquals(3, testPaymentGateway.requestIds().size());
                assertEquals(1, Set.copyOf(testPaymentGateway.requestIds()).size());
            })
            .then(Mono.fromRunnable(testPaymentGateway::succeedPayments))
            .then(orderService.completeOrder(ALICE_ID, ALICE_PAYMENT_ACCOUNT))
            .flatMap(this::completedCheckoutState);

        StepVerifier.create(scenario)
            .assertNext(completed -> {
                assertEquals("COMPLETED", completed.orderStatus());
                assertEquals("CLOSED", completed.basketStatus());
                assertEquals(1, testPaymentGateway.debitCount());
                assertEquals(4, testPaymentGateway.requestIds().size());
                assertEquals(1, Set.copyOf(testPaymentGateway.requestIds()).size());
            })
            .verifyComplete();
    }

    @Test
    void shouldUseDatabaseLeaseAndFenceSupersededPaymentAttempt() {
        UUID firstAttemptId = UUID.randomUUID();
        UUID takeoverAttemptId = UUID.randomUUID();
        long leaseMillis = java.time.Duration.ofMinutes(5).toMillis();

        var scenario = resetDatabase()
            .then(basketService.changeProductCountFromStartPage(ALICE_ID, 1L, PLUS))
            .then(createPendingCheckout())
            .flatMap(orderId -> orderRepository.claimPayment(
                    orderId,
                    ALICE_ID,
                    firstAttemptId,
                    leaseMillis
                )
                .doOnNext(claim -> assertFalse(claim.previouslyAttempted()))
                .then(orderRepository.claimPayment(
                    orderId,
                    ALICE_ID,
                    takeoverAttemptId,
                    leaseMillis
                ).hasElement())
                .doOnNext(assertion -> assertFalse(
                    assertion,
                    "Свежую платёжную аренду удалось перехватить"
                ))
                .then(expirePaymentClaim(orderId))
                .then(orderRepository.claimPayment(
                    orderId,
                    ALICE_ID,
                    takeoverAttemptId,
                    leaseMillis
                ))
                .doOnNext(claim -> assertTrue(claim.previouslyAttempted()))
                .then(Mono.zip(
                    orderRepository.releasePaymentClaim(
                        orderId,
                        ALICE_ID,
                        firstAttemptId
                    ),
                    orderRepository.markCompleted(
                        orderId,
                        ALICE_ID,
                        firstAttemptId
                    ).hasElement(),
                    orderRepository.deletePending(
                        orderId,
                        ALICE_ID,
                        firstAttemptId
                    )
                ))
                .doOnNext(staleResults -> {
                    assertFalse(staleResults.getT1());
                    assertFalse(staleResults.getT2());
                    assertFalse(staleResults.getT3());
                })
                .then(orderRepository.releasePaymentClaim(
                    orderId,
                    ALICE_ID,
                    takeoverAttemptId
                )));

        StepVerifier.create(scenario)
            .expectNext(true)
            .verifyComplete();
    }

    @Test
    void shouldFailWhenOrderDoesNotExist() {
        StepVerifier.create(resetDatabase().then(orderService.getOrder(ALICE_ID, Long.MAX_VALUE)))
            .expectErrorMatches(error -> error instanceof NoSuchElementException
                && error.getMessage().contains(String.valueOf(Long.MAX_VALUE)))
            .verify();
    }

    @Test
    void shouldHideOrdersAndCartsFromOtherUsers() {
        var scenario = resetDatabase()
            .then(basketService.changeProductCountFromStartPage(ALICE_ID, 1L, PLUS))
            .then(orderService.completeOrder(ALICE_ID, ALICE_PAYMENT_ACCOUNT))
            .flatMap(orderId -> Mono.zip(
                Mono.just(orderId),
                orderService.getOrders(BOB_ID),
                orderService.getOrder(BOB_ID, orderId).materialize(),
                basketService.getCart(BOB_ID)
            ));

        StepVerifier.create(scenario)
            .assertNext(result -> {
                assertTrue(result.getT2().orders().isEmpty());
                assertTrue(result.getT3().isOnError());
                assertTrue(result.getT3().getThrowable() instanceof NoSuchElementException);
                assertTrue(result.getT4().items().isEmpty());
            })
            .verifyComplete();
    }

    private Mono<Void> updateProduct(String title, BigDecimal price) {
        return databaseClient.sql("""
                UPDATE market.product
                SET title = :title,
                    price = :price
                WHERE id = 1
                """)
            .bind("title", title)
            .bind("price", price)
            .fetch()
            .rowsUpdated()
            .then();
    }

    private Mono<Long> createPendingCheckout() {
        return databaseClient.sql("""
                WITH checkout_basket AS (
                    UPDATE market.basket
                    SET status = 'CHECKOUT'
                    WHERE user_id = :userId
                      AND status = 'ACTIVE'
                    RETURNING id
                )
                INSERT INTO market."order" (
                    basket_id,
                    user_id,
                    sum,
                    status,
                    payment_account_id,
                    payment_request_id
                )
                SELECT id,
                       :userId,
                       1.00,
                       'PENDING',
                       :paymentAccountId,
                       :paymentRequestId
                FROM checkout_basket
                RETURNING id
                """)
            .bind("userId", ALICE_ID)
            .bind("paymentAccountId", ALICE_PAYMENT_ACCOUNT)
            .bind("paymentRequestId", UUID.randomUUID())
            .map((row, metadata) -> row.get("id", Long.class))
            .one();
    }

    private Mono<Void> expirePaymentClaim(Long orderId) {
        return databaseClient.sql("""
                UPDATE market."order"
                SET payment_attempt_started_at = CURRENT_TIMESTAMP - INTERVAL '10 minutes'
                WHERE id = :orderId
                """)
            .bind("orderId", orderId)
            .fetch()
            .rowsUpdated()
            .then();
    }

    private Mono<CheckoutState> pendingCheckoutState() {
        return databaseClient.sql("""
                SELECT o.id,
                       o.payment_request_id,
                       o.payment_attempted,
                       o.status AS order_status,
                       b.status AS basket_status
                FROM market."order" o
                JOIN market.basket b ON b.id = o.basket_id
                WHERE o.user_id = :userId
                  AND o.status = 'PENDING'
                """)
            .bind("userId", ALICE_ID)
            .map((row, metadata) -> new CheckoutState(
                row.get("id", Long.class),
                row.get("payment_request_id", UUID.class),
                Boolean.TRUE.equals(row.get("payment_attempted", Boolean.class)),
                row.get("order_status", String.class),
                row.get("basket_status", String.class)
            ))
            .one();
    }

    private Mono<CheckoutState> completedCheckoutState(Long orderId) {
        return databaseClient.sql("""
                SELECT o.id,
                       o.payment_request_id,
                       o.payment_attempted,
                       o.status AS order_status,
                       b.status AS basket_status
                FROM market."order" o
                JOIN market.basket b ON b.id = o.basket_id
                WHERE o.id = :orderId
                """)
            .bind("orderId", orderId)
            .map((row, metadata) -> new CheckoutState(
                row.get("id", Long.class),
                row.get("payment_request_id", UUID.class),
                Boolean.TRUE.equals(row.get("payment_attempted", Boolean.class)),
                row.get("order_status", String.class),
                row.get("basket_status", String.class)
            ))
            .one();
    }

    private record CheckoutState(
        Long orderId,
        UUID paymentRequestId,
        boolean paymentAttempted,
        String orderStatus,
        String basketStatus
    ) {
    }

    private record CheckoutRecoveryResult(
        CheckoutState pending,
        CheckoutState completed
    ) {
    }
}
