package ru.yandex.market_app.integration.service;

import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.dao.DataIntegrityViolationException;
import reactor.test.StepVerifier;
import ru.yandex.market_app.exception.ItemNotFoundException;
import ru.yandex.market_app.integration.ReactiveIntegrationTest;
import ru.yandex.market_app.integration.ReactiveIntegrationTestSupport;
import ru.yandex.market_app.service.ProductService;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static ru.yandex.market_app.util.ProductPageableUtil.ProductSort.ALPHA;
import static ru.yandex.market_app.util.ProductPageableUtil.ProductSort.NO;
import static ru.yandex.market_app.util.ProductPageableUtil.ProductSort.PRICE;

@ReactiveIntegrationTest
@RequiredArgsConstructor(onConstructor_ = @Autowired)
class ProductServiceIntegrationTest extends ReactiveIntegrationTestSupport {

    private final ProductService productService;

    @Test
    void shouldRejectProductPriceThatPaymentServiceCannotProcess() {
        var updateToZero = resetDatabase()
            .then(databaseClient.sql("UPDATE market.product SET price = 0 WHERE id = 1")
                .fetch()
                .rowsUpdated());

        StepVerifier.create(updateToZero)
            .expectError(DataIntegrityViolationException.class)
            .verify();
    }

    @Test
    void shouldGetProductWithZeroCartCount() {
        StepVerifier.create(resetDatabase().then(productService.getItem(1L)))
            .assertNext(product -> {
                assertEquals(1L, product.id());
                assertEquals("Ноутбук ASUS VivoBook", product.title());
                assertEquals("15.6\" Full HD, Intel Core i5, 8GB RAM, 512GB SSD", product.description());
                assertEquals("images/product1.jpg", product.imgPath());
                assertEquals(0, BigDecimal.valueOf(54999).compareTo(product.price()));
                assertEquals(0, product.count());
            })
            .verifyComplete();
    }

    @Test
    void shouldFailWhenProductDoesNotExist() {
        StepVerifier.create(resetDatabase().then(productService.getItem(Long.MAX_VALUE)))
            .expectErrorMatches(error -> error instanceof ItemNotFoundException
                && error.getMessage().contains(String.valueOf(Long.MAX_VALUE)))
            .verify();
    }

    @Test
    void shouldSearchTitleAndDescriptionCaseInsensitively() {
        StepVerifier.create(resetDatabase().then(productService.getProducts(
                "ноутБУК",
                NO,
                PageRequest.of(0, 5)
            )))
            .assertNext(result -> {
                assertEquals(1, result.items().size());
                assertEquals("Ноутбук ASUS VivoBook", result.items().getFirst().getFirst().title());
                assertEquals(-1L, result.items().getFirst().get(1).id());
                assertEquals(-1L, result.items().getFirst().get(2).id());
            })
            .verifyComplete();

        StepVerifier.create(productService.getProducts("шумоподавление", NO, PageRequest.of(0, 5)))
            .assertNext(result ->
                assertEquals("Наушники Sony WH-1000XM5", result.items().getFirst().getFirst().title()))
            .verifyComplete();
    }

    @Test
    void shouldTreatSqlLikeSearchAsPlainData() {
        StepVerifier.create(resetDatabase().then(productService.getProducts(
                "' OR 1=1 --",
                NO,
                PageRequest.of(0, 5)
            )))
            .assertNext(result -> assertTrue(result.items().isEmpty()))
            .verifyComplete();
    }

    @Test
    void shouldSortAndBuildStablePagingMetadata() {
        StepVerifier.create(resetDatabase().then(productService.getProducts("", PRICE, PageRequest.of(0, 2))))
            .assertNext(result -> {
                assertEquals("Умная лампа Philips Hue", result.items().getFirst().getFirst().title());
                assertEquals("Фитнес-браслет Xiaomi Band", result.items().getFirst().get(1).title());
                assertEquals(2, result.paging().pageSize());
                assertEquals(1, result.paging().pageNumber());
                assertFalse(result.paging().hasPrevious());
                assertTrue(result.paging().hasNext());
            })
            .verifyComplete();

        StepVerifier.create(productService.getProducts("", ALPHA, PageRequest.of(1, 5)))
            .assertNext(result -> {
                var productIds = result.items().stream()
                    .flatMap(List::stream)
                    .filter(product -> product.id() > 0)
                    .map(product -> product.id())
                    .toList();

                assertEquals(List.of(7L, 15L, 8L, 3L, 1L), productIds);
                assertEquals(2, result.paging().pageNumber());
                assertTrue(result.paging().hasPrevious());
                assertTrue(result.paging().hasNext());
            })
            .verifyComplete();
    }

    @Test
    void shouldApplyDeterministicDefaultAndPriceSorts() {
        StepVerifier.create(resetDatabase().then(productService.getProducts("", NO, PageRequest.of(0, 5))))
            .assertNext(result -> {
                var productIds = result.items().stream()
                    .flatMap(List::stream)
                    .filter(product -> product.id() > 0)
                    .map(product -> product.id())
                    .toList();

                assertEquals(List.of(1L, 2L, 3L, 4L, 5L), productIds);
            })
            .verifyComplete();

        StepVerifier.create(productService.getProducts("", PRICE, PageRequest.of(0, 20)))
            .assertNext(result -> {
                var productIds = result.items().stream()
                    .flatMap(List::stream)
                    .filter(product -> product.id() > 0)
                    .map(product -> product.id())
                    .toList();

                assertEquals(List.of(6L, 14L), productIds.subList(4, 6));
            })
            .verifyComplete();
    }

    @Test
    void shouldPreserveRequestedPagingWhenSearchIsEmptyResult() {
        StepVerifier.create(resetDatabase().then(productService.getProducts(
                "товар-которого-нет",
                NO,
                PageRequest.of(2, 10)
            )))
            .assertNext(result -> {
                assertTrue(result.items().isEmpty());
                assertEquals("товар-которого-нет", result.search());
                assertEquals(NO.name(), result.sort());
                assertEquals(10, result.paging().pageSize());
                assertEquals(3, result.paging().pageNumber());
                assertTrue(result.paging().hasPrevious());
                assertFalse(result.paging().hasNext());
            })
            .verifyComplete();
    }
}
