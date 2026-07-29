package ru.yandex.market_app.integration.service;

import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.yandex.market_app.integration.PostgreTestContainer;
import ru.yandex.market_app.integration.configuration.MarketAppIntegrationConfiguration;
import ru.yandex.market_app.service.BasketService;
import ru.yandex.market_app.service.OrderService;

import java.math.BigDecimal;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static ru.yandex.market_app.model.ProductAction.PLUS;

@SpringJUnitConfig(MarketAppIntegrationConfiguration.class)
@Testcontainers
@ImportTestcontainers(PostgreTestContainer.class)
@RequiredArgsConstructor(onConstructor_ = @Autowired)
@Transactional
class OrderServiceIntegrationTest {

    private static final long PRODUCT_ID = 1L;
    private static final BigDecimal PRODUCT_PRICE = BigDecimal.valueOf(54999);

    private final OrderService orderService;
    private final BasketService basketService;

    @Nested
    class GetOrders {

        @Test
        void shouldReturnEmptyListWhenNoOrders() {
            var result = orderService.getOrders();

            assertNotNull(result);
            assertTrue(result.orders().isEmpty());
        }

        @Test
        void shouldReturnOrdersAfterComplete() {
            basketService.changeProductCountFromStartPage(PRODUCT_ID, PLUS);
            orderService.completeOrder();

            var result = orderService.getOrders();

            assertEquals(1, result.orders().size());
            assertEquals(0, PRODUCT_PRICE.compareTo(result.orders().getFirst().totalSum()));
        }
    }

    @Nested
    class GetOrder {

        @Test
        void shouldGetValidOrder() {
            basketService.changeProductCountFromStartPage(PRODUCT_ID, PLUS);
            Long orderId = orderService.completeOrder();

            var result = orderService.getOrder(orderId);

            assertNotNull(result);
            assertEquals(orderId, result.id());
            assertEquals(1, result.items().size());
            assertEquals("Ноутбук ASUS VivoBook", result.items().getFirst().title());
            assertEquals(0, PRODUCT_PRICE.compareTo(result.totalSum()));
            assertEquals(0, PRODUCT_PRICE.compareTo(result.items().getFirst().price()));
        }

        @Test
        void shouldThrowWhenOrderNotFound() {
            assertThrows(NoSuchElementException.class, () -> orderService.getOrder(Long.MAX_VALUE));
        }
    }

    @Nested
    class CompleteOrder {

        @Test
        void shouldCreateOrderFromActiveBasket() {
            basketService.changeProductCountFromStartPage(PRODUCT_ID, PLUS);

            Long orderId = orderService.completeOrder();

            assertNotNull(orderId);
            assertEquals(orderId, orderService.getOrder(orderId).id());
            assertThrows(NoSuchElementException.class, basketService::findLazyActiveBasket);
        }

        @Test
        void shouldThrowWhenActiveBasketNotExists() {
            assertThrows(NoSuchElementException.class, orderService::completeOrder);
        }

        @Test
        void shouldAllowCreateMultipleOrders() {
            basketService.changeProductCountFromStartPage(PRODUCT_ID, PLUS);
            Long firstOrderId = orderService.completeOrder();

            basketService.changeProductCountFromStartPage(PRODUCT_ID, PLUS);
            Long secondOrderId = orderService.completeOrder();

            assertEquals(2, orderService.getOrders().orders().size());
            assertTrue(firstOrderId < secondOrderId);
        }
    }
}
