package ru.yandex.market_app.integration.service;

import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.yandex.market_app.exception.NotRemoveItemException;
import ru.yandex.market_app.exception.OperationNotSupportedException;
import ru.yandex.market_app.integration.PostgreTestContainer;
import ru.yandex.market_app.integration.configuration.MarketAppIntegrationConfiguration;
import ru.yandex.market_app.model.Basket;
import ru.yandex.market_app.service.BasketService;

import java.math.BigDecimal;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static ru.yandex.market_app.model.ProductAction.DELETE;
import static ru.yandex.market_app.model.ProductAction.MINUS;
import static ru.yandex.market_app.model.ProductAction.ONE_PRODUCT_VALUE;
import static ru.yandex.market_app.model.ProductAction.PLUS;
import static ru.yandex.market_app.model.ProductAction.ZERO_PRODUCT_VALUE;

@SpringJUnitConfig(MarketAppIntegrationConfiguration.class)
@Testcontainers
@ImportTestcontainers(PostgreTestContainer.class)
@RequiredArgsConstructor(onConstructor_ = @Autowired)
@Transactional
class BasketServiceIntegrationTest {

    private static final long PRODUCT_ID = 1L;
    private static final long OTHER_PRODUCT_ID = 2L;
    private static final BigDecimal PRODUCT_PRICE = BigDecimal.valueOf(54999);

    private final BasketService basketService;

    @Nested
    class ChangeProductCountFromStartPage {

        @Test
        void shouldAddProductWhenPlus() {
            assertDoesNotThrow(() -> basketService.changeProductCountFromStartPage(PRODUCT_ID, PLUS));

            var cart = basketService.getCart();
            assertEquals(1, cart.items().size());
            assertEquals(ONE_PRODUCT_VALUE, cart.items().getFirst().count());
            assertEquals(0, PRODUCT_PRICE.compareTo(cart.total()));
        }

        @Test
        void shouldIncreaseCountWhenPlusTwice() {
            basketService.changeProductCountFromStartPage(PRODUCT_ID, PLUS);
            basketService.changeProductCountFromStartPage(PRODUCT_ID, PLUS);

            var cart = basketService.getCart();
            assertEquals(2, cart.items().getFirst().count());
            assertEquals(0, PRODUCT_PRICE.multiply(BigDecimal.valueOf(2)).compareTo(cart.total()));
        }

        @Test
        void shouldDecreaseCountWhenMinus() {
            basketService.changeProductCountFromStartPage(PRODUCT_ID, PLUS);
            basketService.changeProductCountFromStartPage(PRODUCT_ID, PLUS);
            basketService.changeProductCountFromStartPage(PRODUCT_ID, MINUS);

            assertEquals(ONE_PRODUCT_VALUE, basketService.getCart().items().getFirst().count());
        }

        @Test
        void shouldRemoveProductWhenMinusAtCountOne() {
            basketService.changeProductCountFromStartPage(PRODUCT_ID, PLUS);
            basketService.changeProductCountFromStartPage(PRODUCT_ID, MINUS);

            assertTrue(basketService.getCart().items().isEmpty());
        }

        @Test
        void shouldThrowWhenDelete() {
            assertThrows(
                OperationNotSupportedException.class,
                () -> basketService.changeProductCountFromStartPage(PRODUCT_ID, DELETE)
            );
        }

        @Test
        void shouldThrowWhenMinusAndBasketNotCreated() {
            assertThrows(
                NotRemoveItemException.class,
                () -> basketService.changeProductCountFromStartPage(PRODUCT_ID, MINUS)
            );
        }

        @Test
        void shouldThrowWhenMinusAndProductNotInBasket() {
            basketService.changeProductCountFromStartPage(PRODUCT_ID, PLUS);

            assertThrows(
                NotRemoveItemException.class,
                () -> basketService.changeProductCountFromStartPage(OTHER_PRODUCT_ID, MINUS)
            );
        }

        @Test
        void shouldThrowWhenProductNotFound() {
            assertThrows(
                NoSuchElementException.class,
                () -> basketService.changeProductCountFromStartPage(Long.MAX_VALUE, PLUS)
            );
        }
    }

    @Nested
    class ChangeProductCountFromItemPage {

        @Test
        void shouldReturnProductWithCountWhenPlus() {
            var result = basketService.changeProductCountFromItemPage(PRODUCT_ID, PLUS);

            assertNotNull(result);
            assertEquals(PRODUCT_ID, result.id());
            assertEquals("Ноутбук ASUS VivoBook", result.title());
            assertEquals(ONE_PRODUCT_VALUE, result.count());
            assertEquals(0, PRODUCT_PRICE.compareTo(result.price()));
        }

        @Test
        void shouldReturnZeroCountWhenMinusRemovesLastItem() {
            basketService.changeProductCountFromItemPage(PRODUCT_ID, PLUS);

            var result = basketService.changeProductCountFromItemPage(PRODUCT_ID, MINUS);

            assertEquals(ZERO_PRODUCT_VALUE, result.count());
        }

        @Test
        void shouldThrowWhenDelete() {
            assertThrows(
                OperationNotSupportedException.class,
                () -> basketService.changeProductCountFromItemPage(PRODUCT_ID, DELETE)
            );
        }

        @Test
        void shouldThrowWhenProductNotFound() {
            assertThrows(
                NoSuchElementException.class,
                () -> basketService.changeProductCountFromItemPage(Long.MAX_VALUE, PLUS)
            );
        }
    }

    @Nested
    class ChangeProductCountFromCartPage {

        @Test
        void shouldAddProductWhenPlus() {
            basketService.changeProductCountFromCartPage(PRODUCT_ID, PLUS);

            assertEquals(ONE_PRODUCT_VALUE, basketService.getCart().items().getFirst().count());
        }

        @Test
        void shouldRemoveProductWhenDelete() {
            basketService.changeProductCountFromCartPage(PRODUCT_ID, PLUS);

            basketService.changeProductCountFromCartPage(PRODUCT_ID, DELETE);

            assertTrue(basketService.getCart().items().isEmpty());
        }

        @Test
        void shouldThrowWhenMinusAndBasketNotCreated() {
            assertThrows(
                NotRemoveItemException.class,
                () -> basketService.changeProductCountFromCartPage(PRODUCT_ID, MINUS)
            );
        }

        @Test
        void shouldThrowWhenProductNotFound() {
            assertThrows(
                NoSuchElementException.class,
                () -> basketService.changeProductCountFromCartPage(Long.MAX_VALUE, PLUS)
            );
        }
    }

    @Nested
    class GetCart {

        @Test
        void shouldReturnEmptyCartWhenNoItems() {
            var cart = basketService.getCart();

            assertNotNull(cart);
            assertTrue(cart.items().isEmpty());
            assertEquals(0, BigDecimal.ZERO.compareTo(cart.total()));
        }

        @Test
        void shouldReturnItemsAfterAddingProduct() {
            basketService.changeProductCountFromStartPage(PRODUCT_ID, PLUS);

            var cart = basketService.getCart();

            assertEquals(1, cart.items().size());
            assertEquals("Ноутбук ASUS VivoBook", cart.items().getFirst().title());
            assertEquals(0, PRODUCT_PRICE.compareTo(cart.total()));
        }
    }

    @Nested
    class FindLazyActiveBasket {

        @Test
        void shouldThrowWhenActiveBasketNotExists() {
            assertThrows(NoSuchElementException.class, basketService::findLazyActiveBasket);
        }

        @Test
        void shouldReturnActiveBasketWithTotalSum() {
            basketService.changeProductCountFromStartPage(PRODUCT_ID, PLUS);

            var basket = basketService.findLazyActiveBasket();

            assertNotNull(basket.id());
            assertEquals(0, PRODUCT_PRICE.compareTo(basket.totalSum()));
        }
    }

    @Nested
    class GetReferenceById {

        @Test
        void shouldReturnBasketById() {
            basketService.changeProductCountFromStartPage(PRODUCT_ID, PLUS);
            var basketDto = basketService.findLazyActiveBasket();

            Basket basket = basketService.getReferenceById(basketDto.id());

            assertEquals(basketDto.id(), basket.getId());
            assertEquals(Basket.Status.ACTIVE, basket.getStatus());
        }
    }

    @Nested
    class CloseActiveBasket {

        @Test
        void shouldCloseActiveBasket() {
            basketService.changeProductCountFromStartPage(PRODUCT_ID, PLUS);

            basketService.closeActiveBasket();

            assertThrows(NoSuchElementException.class, basketService::findLazyActiveBasket);
        }

        @Test
        void shouldAllowCreateNewBasketAfterClose() {
            basketService.changeProductCountFromStartPage(PRODUCT_ID, PLUS);
            basketService.closeActiveBasket();

            assertDoesNotThrow(() -> basketService.changeProductCountFromStartPage(PRODUCT_ID, PLUS));
            assertEquals(1, basketService.getCart().items().size());
        }
    }
}
