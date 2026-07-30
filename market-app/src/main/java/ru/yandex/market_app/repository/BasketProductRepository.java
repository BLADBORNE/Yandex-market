package ru.yandex.market_app.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.yandex.market_app.dto.ProductCount;

@Repository
@RequiredArgsConstructor
public class BasketProductRepository {

    private final DatabaseClient databaseClient;

    public Flux<ProductCount> findActiveProductCounts() {
        return databaseClient.sql("""
                SELECT bp.product_id, bp.count
                FROM market.basket_product bp
                JOIN market.basket b ON b.id = bp.basket_id
                WHERE b.status = 'ACTIVE'
                ORDER BY bp.product_id
                """)
            .map((row, metadata) -> new ProductCount(
                row.get("product_id", Long.class),
                row.get("count", Integer.class)
            ))
            .all();
    }

    public Mono<Integer> findActiveProductCount(Long productId) {
        return databaseClient.sql("""
                SELECT bp.count
                FROM market.basket_product bp
                JOIN market.basket b ON b.id = bp.basket_id
                WHERE b.status = 'ACTIVE'
                  AND bp.product_id = :productId
                """)
            .bind("productId", productId)
            .map((row, metadata) -> row.get("count", Integer.class))
            .one();
    }

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
