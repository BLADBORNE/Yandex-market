package ru.yandex.market_app.integration;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Mono;
import ru.yandex.market_app.cache.ProductCatalogCache;

public abstract class ReactiveIntegrationTestSupport {

    @Autowired
    protected DatabaseClient databaseClient;

    @Autowired
    protected ProductCatalogCache productCatalogCache;

    @Autowired
    protected PostgreTestContainer.TestPaymentGateway testPaymentGateway;

    protected Mono<Void> resetDatabase() {
        return Mono.fromRunnable(testPaymentGateway::reset)
            .then(databaseClient.sql("""
                TRUNCATE TABLE market.order_item, market."order", market.basket_product, market.basket
                RESTART IDENTITY CASCADE
                """)
                .fetch()
                .rowsUpdated())
            .then(productCatalogCache.evict());
    }
}
