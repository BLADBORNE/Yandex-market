package ru.yandex.market_app.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.yandex.market_app.cache.CachedProduct;
import ru.yandex.market_app.cache.CachedProductCatalog;
import ru.yandex.market_app.cache.ProductCacheAccessException;
import ru.yandex.market_app.cache.ProductCatalogCache;
import ru.yandex.market_app.model.Product;
import ru.yandex.market_app.repository.ProductRepository;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductCatalogProviderTest {

    @Mock
    private ProductCatalogCache productCatalogCache;

    @Mock
    private ProductRepository productRepository;

    private ProductCatalogProvider provider;

    @BeforeEach
    void setUp() {
        provider = new ProductCatalogProvider(productCatalogCache, productRepository);
    }

    @Test
    void shouldReturnCacheHitWithoutDatabaseCall() {
        var catalog = new CachedProductCatalog(List.of(cachedProduct(1L)));
        when(productCatalogCache.get()).thenReturn(Mono.just(catalog));

        StepVerifier.create(provider.getCatalog())
            .expectNext(catalog)
            .verifyComplete();

        verify(productRepository, never()).findAll();
        verify(productCatalogCache, never()).put(catalog);
    }

    @Test
    void shouldLoadSortAndCacheCatalogOnMiss() {
        when(productCatalogCache.get()).thenReturn(Mono.empty());
        when(productRepository.findAll()).thenReturn(Flux.just(product(2L), product(1L)));
        when(productCatalogCache.put(new CachedProductCatalog(List.of(cachedProduct(1L), cachedProduct(2L)))))
            .thenReturn(Mono.empty());

        StepVerifier.create(provider.getCatalog())
            .assertNext(catalog -> org.junit.jupiter.api.Assertions.assertEquals(
                List.of(1L, 2L),
                catalog.products().stream().map(CachedProduct::id).toList()
            ))
            .verifyComplete();
    }

    @Test
    void shouldFallBackToDatabaseWhenRedisReadFails() {
        when(productCatalogCache.get())
            .thenReturn(Mono.error(new ProductCacheAccessException("redis read failed")));
        when(productRepository.findAll()).thenReturn(Flux.just(product(1L)));
        when(productCatalogCache.put(new CachedProductCatalog(List.of(cachedProduct(1L)))))
            .thenReturn(Mono.error(new ProductCacheAccessException("redis write failed")));

        StepVerifier.create(provider.getCatalog())
            .assertNext(catalog -> org.junit.jupiter.api.Assertions.assertEquals(1, catalog.products().size()))
            .verifyComplete();
    }

    @Test
    void shouldReturnDatabaseDataWhenRedisWriteFails() {
        when(productCatalogCache.get()).thenReturn(Mono.empty());
        when(productRepository.findAll()).thenReturn(Flux.just(product(1L)));
        when(productCatalogCache.put(new CachedProductCatalog(List.of(cachedProduct(1L)))))
            .thenReturn(Mono.error(new ProductCacheAccessException("redis write failed")));

        StepVerifier.create(provider.getCatalog())
            .assertNext(catalog -> org.junit.jupiter.api.Assertions.assertEquals(1L, catalog.products().getFirst().id()))
            .verifyComplete();
    }

    @Test
    void shouldPropagateDatabaseFailure() {
        var databaseFailure = new IllegalStateException("database failed");
        when(productCatalogCache.get()).thenReturn(Mono.empty());
        when(productRepository.findAll()).thenReturn(Flux.error(databaseFailure));

        StepVerifier.create(provider.getCatalog())
            .expectErrorMatches(error -> error == databaseFailure)
            .verify();
    }

    private Product product(Long id) {
        return Product.builder()
            .id(id)
            .title("Товар " + id)
            .description("Описание " + id)
            .imgPath("images/" + id + ".jpg")
            .price(BigDecimal.valueOf(100L + id))
            .build();
    }

    private CachedProduct cachedProduct(Long id) {
        return CachedProduct.from(product(id));
    }
}
