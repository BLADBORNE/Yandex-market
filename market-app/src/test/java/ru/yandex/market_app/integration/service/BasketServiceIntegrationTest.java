package ru.yandex.market_app.integration.service;

import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.yandex.market_app.exception.NotRemoveItemException;
import ru.yandex.market_app.exception.OperationNotSupportedException;
import ru.yandex.market_app.integration.ReactiveIntegrationTest;
import ru.yandex.market_app.integration.ReactiveIntegrationTestSupport;
import ru.yandex.market_app.model.Basket;
import ru.yandex.market_app.service.BasketService;

import java.math.BigDecimal;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static ru.yandex.market_app.model.ProductAction.DELETE;
import static ru.yandex.market_app.model.ProductAction.MINUS;
import static ru.yandex.market_app.model.ProductAction.PLUS;

@ReactiveIntegrationTest
@RequiredArgsConstructor(onConstructor_ = @Autowired)
class BasketServiceIntegrationTest extends ReactiveIntegrationTestSupport {

    private static final long PRODUCT_ID = 1L;
    private static final BigDecimal PRODUCT_PRICE = BigDecimal.valueOf(54999);

    private final BasketService basketService;

    @Test
    void shouldReturnEmptyCart() {
        StepVerifier.create(resetDatabase().then(basketService.getCart(ALICE_ID)))
            .assertNext(cart -> {
                assertTrue(cart.items().isEmpty());
                assertEquals(0, BigDecimal.ZERO.compareTo(cart.total()));
            })
            .verifyComplete();
    }

    @Test
    void shouldAddIncreaseDecreaseAndRemoveProduct() {
        var scenario = resetDatabase()
            .then(basketService.changeProductCountFromStartPage(ALICE_ID, PRODUCT_ID, PLUS))
            .then(basketService.changeProductCountFromItemPage(ALICE_ID, PRODUCT_ID, PLUS))
            .then(basketService.changeProductCountFromCartPage(ALICE_ID, PRODUCT_ID, MINUS))
            .then(basketService.getCart(ALICE_ID))
            .doOnNext(cart -> {
                assertEquals(1, cart.items().size());
                assertEquals(1, cart.items().getFirst().count());
                assertEquals(0, PRODUCT_PRICE.compareTo(cart.total()));
            })
            .then(basketService.changeProductCountFromCartPage(ALICE_ID, PRODUCT_ID, DELETE))
            .then(basketService.getCart(ALICE_ID));

        StepVerifier.create(scenario)
            .assertNext(cart -> {
                assertTrue(cart.items().isEmpty());
                assertEquals(0, BigDecimal.ZERO.compareTo(cart.total()));
            })
            .verifyComplete();
    }

    @Test
    void shouldRejectDeleteOutsideCart() {
        StepVerifier.create(resetDatabase()
                .then(basketService.changeProductCountFromStartPage(ALICE_ID, PRODUCT_ID, DELETE)))
            .expectError(OperationNotSupportedException.class)
            .verify();

        StepVerifier.create(basketService.changeProductCountFromItemPage(ALICE_ID, PRODUCT_ID, DELETE))
            .expectError(OperationNotSupportedException.class)
            .verify();
    }

    @Test
    void shouldRejectRemovalWhenCartOrItemIsMissing() {
        StepVerifier.create(resetDatabase()
                .then(basketService.changeProductCountFromCartPage(ALICE_ID, PRODUCT_ID, MINUS)))
            .expectErrorMatches(error -> error instanceof NotRemoveItemException
                && error.getMessage().contains("Корзина не создана"))
            .verify();

        var missingItemScenario = resetDatabase()
            .then(basketService.changeProductCountFromStartPage(ALICE_ID, PRODUCT_ID, PLUS))
            .then(basketService.changeProductCountFromCartPage(ALICE_ID, 2L, MINUS));

        StepVerifier.create(missingItemScenario)
            .expectErrorMatches(error -> error instanceof NotRemoveItemException
                && error.getMessage().contains("отсутствует в корзине"))
            .verify();
    }

    @Test
    void shouldFailForUnknownProduct() {
        StepVerifier.create(resetDatabase()
                .then(basketService.changeProductCountFromStartPage(ALICE_ID, Long.MAX_VALUE, PLUS)))
            .expectError(NoSuchElementException.class)
            .verify();
    }

    @Test
    void shouldKeepOneActiveBasketAfterItBecomesEmpty() {
        var scenario = resetDatabase()
            .then(basketService.changeProductCountFromStartPage(ALICE_ID, PRODUCT_ID, PLUS))
            .then(basketService.changeProductCountFromStartPage(ALICE_ID, PRODUCT_ID, MINUS))
            .then(basketService.changeProductCountFromStartPage(ALICE_ID, 2L, PLUS))
            .then(basketService.getCart(ALICE_ID));

        StepVerifier.create(scenario)
            .assertNext(cart -> {
                assertEquals(1, cart.items().size());
                assertEquals(2L, cart.items().getFirst().id());
            })
            .verifyComplete();
    }

    @Test
    void shouldSerializeConcurrentIncrementsWithoutLosingUpdates() {
        int increments = 12;
        var scenario = resetDatabase()
            .thenMany(Flux.range(0, increments)
                .flatMap(
                    ignored -> basketService.changeProductCountFromStartPage(ALICE_ID, PRODUCT_ID, PLUS),
                    increments
                ))
            .then(basketService.getCart(ALICE_ID));

        StepVerifier.create(scenario)
            .assertNext(cart -> {
                assertEquals(increments, cart.items().getFirst().count());
                assertEquals(
                    0,
                    PRODUCT_PRICE.multiply(BigDecimal.valueOf(increments)).compareTo(cart.total())
                );
            })
            .verifyComplete();
    }

    @Test
    void shouldExposeAndCloseExactActiveBasket() {
        var scenario = resetDatabase()
            .then(basketService.changeProductCountFromStartPage(ALICE_ID, PRODUCT_ID, PLUS))
            .then(basketService.findLazyActiveBasket(ALICE_ID))
            .flatMap(basketDto -> {
                assertNotNull(basketDto.id());
                assertEquals(0, PRODUCT_PRICE.compareTo(basketDto.totalSum()));
                return basketService.getReferenceById(ALICE_ID, basketDto.id())
                    .doOnNext(basket -> assertEquals(Basket.Status.ACTIVE, basket.getStatus()))
                    .then(basketService.closeActiveBasket(ALICE_ID, basketDto.id()));
            })
            .then(basketService.findLazyActiveBasket(ALICE_ID));

        StepVerifier.create(scenario)
            .expectError(NoSuchElementException.class)
            .verify();
    }

    @Test
    void shouldKeepActiveCartsStrictlyIsolatedByUser() {
        var scenario = resetDatabase()
            .then(basketService.changeProductCountFromStartPage(ALICE_ID, PRODUCT_ID, PLUS))
            .then(basketService.changeProductCountFromStartPage(BOB_ID, 2L, PLUS))
            .then(Mono.zip(
                basketService.getCart(ALICE_ID),
                basketService.getCart(BOB_ID)
            ));

        StepVerifier.create(scenario)
            .assertNext(carts -> {
                assertEquals(PRODUCT_ID, carts.getT1().items().getFirst().id());
                assertEquals(2L, carts.getT2().items().getFirst().id());
            })
            .verifyComplete();
    }
}
