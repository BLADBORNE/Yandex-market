package ru.yandex.market_app.integration;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Mono;

public abstract class ReactiveIntegrationTestSupport {

    @Autowired
    protected DatabaseClient databaseClient;

    protected Mono<Void> resetDatabase() {
        return databaseClient.sql("""
                TRUNCATE TABLE market.order_item, market."order", market.basket_product, market.basket
                RESTART IDENTITY CASCADE
                """)
            .fetch()
            .rowsUpdated()
            .then();
    }
}
