package ru.yandex.market_app.integration.service;

import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.yandex.market_app.exception.ItemNotFoundException;
import ru.yandex.market_app.integration.PostgreTestContainer;
import ru.yandex.market_app.integration.configuration.MarketAppIntegrationConfiguration;
import ru.yandex.market_app.service.ProductService;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static ru.yandex.market_app.util.ProductPageableUtil.ProductSort.NO;

@SpringJUnitConfig(MarketAppIntegrationConfiguration.class)
@Testcontainers
@ImportTestcontainers(PostgreTestContainer.class)
@RequiredArgsConstructor(onConstructor_ = @Autowired)
@Transactional(readOnly = true)
class ProductServiceIntegrationTest {

    private final ProductService productService;

    @Nested
    class GetItem {

        @Test
        void shouldGetValidItem() {
            assertDoesNotThrow(() -> productService.getItem(1L));
            var result = productService.getItem(1L);
            assertNotNull(result);
            assertEquals(1L, result.id());
            assertEquals("Ноутбук ASUS VivoBook", result.title());
            assertEquals("15.6\" Full HD, Intel Core i5, 8GB RAM, 512GB SSD", result.description());
            assertEquals("images/product1.jpg", result.imgPath());
            assertEquals(0, BigDecimal.valueOf(54999).compareTo(result.price()));
        }

        @Test
        void shouldThrownExceptionWhenNotExistsItem() {
            assertThrows(ItemNotFoundException.class, () -> productService.getItem(Long.MAX_VALUE));
        }
    }

    @Nested
    class GetSearchItem {

        @Test
        void shouldGetItemBySearch() {
            var result = productService.getProducts("Ноутбук", NO, PageRequest.of(0, 1));
            assertNotNull(result);
            assertFalse(result.items().isEmpty());
            assertEquals(1, result.items().size());
            assertEquals(
                "15.6\" Full HD, Intel Core i5, 8GB RAM, 512GB SSD",
                result.items().getFirst().getFirst().description()
            );
        }

        @Test
        void shouldGetAllItemsIfSearchIsEmpty() {
            var result = productService.getProducts("", NO, PageRequest.of(0, 20));
            assertNotNull(result);
            assertFalse(result.items().isEmpty());
        }
    }
}
