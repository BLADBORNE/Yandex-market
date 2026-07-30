package ru.yandex.market_app.integration.service;

import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Signal;
import reactor.test.StepVerifier;
import ru.yandex.market_app.integration.ReactiveIntegrationTest;
import ru.yandex.market_app.integration.ReactiveIntegrationTestSupport;
import ru.yandex.market_app.service.BasketService;
import ru.yandex.market_app.service.OrderService;

import java.math.BigDecimal;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @Test
    void shouldReturnEmptyOrderList() {
        StepVerifier.create(resetDatabase().then(orderService.getOrders()))
            .assertNext(result -> assertTrue(result.orders().isEmpty()))
            .verifyComplete();
    }

    @Test
    void shouldRejectCheckoutWithoutNonEmptyActiveCart() {
        StepVerifier.create(resetDatabase().then(orderService.completeOrder()))
            .expectError(NoSuchElementException.class)
            .verify();

        var emptyActiveCart = resetDatabase()
            .then(basketService.changeProductCountFromStartPage(1L, PLUS))
            .then(basketService.changeProductCountFromStartPage(1L, ru.yandex.market_app.model.ProductAction.MINUS))
            .then(orderService.completeOrder());

        StepVerifier.create(emptyActiveCart)
            .expectErrorMatches(error -> error instanceof NoSuchElementException
                && error.getMessage().contains("пуста"))
            .verify();
    }

    @Test
    void shouldCreateOrderSnapshotCloseCartAndReturnOrder() {
        BigDecimal expectedTotal = FIRST_PRODUCT_PRICE.multiply(BigDecimal.TWO).add(SECOND_PRODUCT_PRICE);

        var scenario = resetDatabase()
            .then(basketService.changeProductCountFromStartPage(1L, PLUS))
            .then(basketService.changeProductCountFromStartPage(1L, PLUS))
            .then(basketService.changeProductCountFromStartPage(2L, PLUS))
            .then(orderService.completeOrder())
            .flatMap(orderId -> orderService.getOrder(orderId)
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
                .then(basketService.getCart())
                .doOnNext(cart -> assertTrue(cart.items().isEmpty()))
                .thenReturn(orderId))
            .flatMap(orderId -> orderService.getOrders()
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
            .then(basketService.changeProductCountFromStartPage(1L, PLUS))
            .then(orderService.completeOrder())
            .flatMap(firstId -> basketService.changeProductCountFromStartPage(2L, PLUS)
                .then(orderService.completeOrder())
                .map(secondId -> {
                    assertNotEquals(firstId, secondId);
                    return secondId;
                }))
            .then(orderService.getOrders());

        StepVerifier.create(scenario)
            .assertNext(result -> assertEquals(2, result.orders().size()))
            .verifyComplete();
    }

    @Test
    void shouldKeepOrderItemSnapshotWhenCatalogProductChanges() {
        String originalTitle = "Ноутбук ASUS VivoBook";

        var scenario = resetDatabase()
            .then(basketService.changeProductCountFromStartPage(1L, PLUS))
            .then(orderService.completeOrder())
            .flatMap(orderId -> updateProduct("Обновлённый товар", BigDecimal.ONE)
                .then(orderService.getOrder(orderId)))
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
    void shouldAllowOnlyOneOfTwoConcurrentCheckouts() {
        var scenario = resetDatabase()
            .then(basketService.changeProductCountFromStartPage(1L, PLUS))
            .then(Mono.zip(
                orderService.completeOrder().materialize(),
                orderService.completeOrder().materialize()
            ));

        StepVerifier.create(scenario)
            .assertNext(signals -> {
                Signal<Long> first = signals.getT1();
                Signal<Long> second = signals.getT2();
                long successes = java.util.stream.Stream.of(first, second).filter(Signal::hasValue).count();
                long failures = java.util.stream.Stream.of(first, second).filter(Signal::isOnError).count();
                assertEquals(1, successes);
                assertEquals(1, failures);
            })
            .verifyComplete();
    }

    @Test
    void shouldFailWhenOrderDoesNotExist() {
        StepVerifier.create(resetDatabase().then(orderService.getOrder(Long.MAX_VALUE)))
            .expectErrorMatches(error -> error instanceof NoSuchElementException
                && error.getMessage().contains(String.valueOf(Long.MAX_VALUE)))
            .verify();
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
}
