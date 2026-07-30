package ru.yandex.market_app.integration.cache;

import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.yandex.market_app.cache.CachedProduct;
import ru.yandex.market_app.cache.CachedProductCatalog;
import ru.yandex.market_app.configuration.ProductCacheProperties;
import ru.yandex.market_app.integration.ReactiveIntegrationTest;
import ru.yandex.market_app.integration.ReactiveIntegrationTestSupport;
import ru.yandex.market_app.service.BasketService;
import ru.yandex.market_app.service.ProductService;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static ru.yandex.market_app.model.ProductAction.PLUS;
import static ru.yandex.market_app.util.ProductPageableUtil.ProductSort.NO;

@ReactiveIntegrationTest
@RequiredArgsConstructor(onConstructor_ = @Autowired)
class ProductCacheIntegrationTest extends ReactiveIntegrationTestSupport {

    private static final String TEMPORARY_TITLE = "Товар для проверки Redis";

    private final ProductService productService;
    private final BasketService basketService;
    private final ReactiveRedisTemplate<String, CachedProductCatalog> redisTemplate;
    private final ProductCacheProperties cacheProperties;

    @Test
    void shouldRoundTripTypedCatalogAndApplyTtl() {
        var catalog = new CachedProductCatalog(List.of(new CachedProduct(
            999L,
            "images/redis.jpg",
            "Товар из кеша",
            "Описание с кириллицей",
            new BigDecimal("123.45")
        )));

        var scenario = resetDatabase()
            .then(productCatalogCache.put(catalog))
            .then(Mono.zip(
                productCatalogCache.get(),
                redisTemplate.getExpire(cacheProperties.key())
            ));

        StepVerifier.create(scenario)
            .assertNext(tuple -> {
                assertEquals(catalog, tuple.getT1());
                Duration remainingTtl = tuple.getT2();
                assertTrue(remainingTtl.isPositive());
                assertTrue(remainingTtl.compareTo(cacheProperties.ttl()) <= 0);
            })
            .verifyComplete();
    }

    @Test
    void shouldUseCachedListUntilExactKeyIsEvicted() {
        var scenario = resetDatabase()
            .then(productService.getProducts("", NO, PageRequest.of(0, 100), null))
            .then(Mono.usingWhen(
                insertTemporaryProduct(),
                productId -> productService.getProducts(TEMPORARY_TITLE, NO, PageRequest.of(0, 5), null)
                    .doOnNext(cachedResult -> assertTrue(cachedResult.items().isEmpty()))
                    .then(productCatalogCache.evict())
                    .then(productService.getProducts(TEMPORARY_TITLE, NO, PageRequest.of(0, 5), null))
                    .doOnNext(refreshedResult -> {
                        assertFalse(refreshedResult.items().isEmpty());
                        assertEquals(TEMPORARY_TITLE, refreshedResult.items().getFirst().getFirst().title());
                    })
                    .then(),
                this::deleteTemporaryProduct,
                (productId, error) -> deleteTemporaryProduct(productId),
                this::deleteTemporaryProduct
            ));

        StepVerifier.create(scenario).verifyComplete();
    }

    @Test
    void shouldUseCachedProductDetailsAndRefreshAfterEviction() {
        String updatedTitle = "Ноутбук после обновления БД";

        var scenario = resetDatabase()
            .then(Mono.usingWhen(
                productService.getItem(1L, null),
                original -> updateTitle(1L, updatedTitle)
                    .then(productService.getItem(1L, null))
                    .doOnNext(cached -> assertEquals(original.title(), cached.title()))
                    .then(productCatalogCache.evict())
                    .then(productService.getItem(1L, null))
                    .doOnNext(refreshed -> assertEquals(updatedTitle, refreshed.title()))
                    .then(),
                original -> restoreTitle(original.title()),
                (original, error) -> restoreTitle(original.title()),
                original -> restoreTitle(original.title())
            ));

        StepVerifier.create(scenario).verifyComplete();
    }

    @Test
    void shouldHydrateCartMetadataFromCacheAndKeepCountInDatabase() {
        BigDecimal updatedPrice = BigDecimal.ONE;

        var scenario = resetDatabase()
            .then(basketService.changeProductCountFromStartPage(ALICE_ID, 1L, PLUS))
            .then(basketService.getCart(ALICE_ID))
            .flatMap(originalCart -> Mono.usingWhen(
                Mono.just(originalCart.items().getFirst().price()),
                originalPrice -> updatePrice(1L, updatedPrice)
                    .then(basketService.changeProductCountFromStartPage(ALICE_ID, 1L, PLUS))
                    .then(basketService.getCart(ALICE_ID))
                    .doOnNext(cachedCart -> {
                        assertEquals(2, cachedCart.items().getFirst().count());
                        assertEquals(0, originalPrice.compareTo(cachedCart.items().getFirst().price()));
                    })
                    .then(productCatalogCache.evict())
                    .then(basketService.getCart(ALICE_ID))
                    .doOnNext(refreshedCart -> {
                        assertEquals(2, refreshedCart.items().getFirst().count());
                        assertEquals(0, updatedPrice.compareTo(refreshedCart.items().getFirst().price()));
                    })
                    .then(),
                originalPrice -> restorePrice(originalPrice),
                (originalPrice, error) -> restorePrice(originalPrice),
                this::restorePrice
            ));

        StepVerifier.create(scenario).verifyComplete();
    }

    private Mono<Long> insertTemporaryProduct() {
        return databaseClient.sql("""
                INSERT INTO market.product (title, description, img_path, price)
                VALUES (:title, 'Проверка cache-aside', 'images/cache-test.jpg', 321.00)
                RETURNING id
                """)
            .bind("title", TEMPORARY_TITLE)
            .map((row, metadata) -> row.get("id", Long.class))
            .one();
    }

    private Mono<Void> deleteTemporaryProduct(Long productId) {
        return databaseClient.sql("DELETE FROM market.product WHERE id = :id")
            .bind("id", productId)
            .fetch()
            .rowsUpdated()
            .then(productCatalogCache.evict());
    }

    private Mono<Void> updateTitle(Long productId, String title) {
        return databaseClient.sql("UPDATE market.product SET title = :title WHERE id = :id")
            .bind("title", title)
            .bind("id", productId)
            .fetch()
            .rowsUpdated()
            .then();
    }

    private Mono<Void> restoreTitle(String title) {
        return updateTitle(1L, title).then(productCatalogCache.evict());
    }

    private Mono<Void> updatePrice(Long productId, BigDecimal price) {
        return databaseClient.sql("UPDATE market.product SET price = :price WHERE id = :id")
            .bind("price", price)
            .bind("id", productId)
            .fetch()
            .rowsUpdated()
            .then();
    }

    private Mono<Void> restorePrice(BigDecimal price) {
        return updatePrice(1L, price).then(productCatalogCache.evict());
    }
}
