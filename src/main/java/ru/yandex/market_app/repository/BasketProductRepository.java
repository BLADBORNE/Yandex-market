package ru.yandex.market_app.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

@Repository
@RequiredArgsConstructor
public class BasketProductRepository {

    private final DatabaseClient databaseClient;

    public Mono<Integer> increment(Long basketId, Long productId) {
        return databaseClient.sql("""
                INSERT INTO market.basket_product (basket_id, product_id, count)
                VALUES (:basketId, :productId, 1)
                ON CONFLICT (product_id, basket_id)
                DO UPDATE SET count = market.basket_product.count + 1
                RETURNING count
                """)
            .bind("basketId", basketId)
            .bind("productId", productId)
            .map((row, metadata) -> row.get("count", Integer.class))
            .one();
    }

    public Mono<Integer> decrement(Long basketId, Long productId) {
        return databaseClient.sql("""
                UPDATE market.basket_product
                SET count = count - 1
                WHERE basket_id = :basketId
                  AND product_id = :productId
                  AND count > 1
                RETURNING count
                """)
            .bind("basketId", basketId)
            .bind("productId", productId)
            .map((row, metadata) -> row.get("count", Integer.class))
            .one()
            .switchIfEmpty(deleteLast(basketId, productId));
    }

    public Mono<Integer> delete(Long basketId, Long productId) {
        return databaseClient.sql("""
                DELETE FROM market.basket_product
                WHERE basket_id = :basketId
                  AND product_id = :productId
                RETURNING 0 AS count
                """)
            .bind("basketId", basketId)
            .bind("productId", productId)
            .map((row, metadata) -> row.get("count", Integer.class))
            .one();
    }

    private Mono<Integer> deleteLast(Long basketId, Long productId) {
        return databaseClient.sql("""
                DELETE FROM market.basket_product
                WHERE basket_id = :basketId
                  AND product_id = :productId
                  AND count = 1
                RETURNING 0 AS count
                """)
            .bind("basketId", basketId)
            .bind("productId", productId)
            .map((row, metadata) -> row.get("count", Integer.class))
            .one();
    }
}
