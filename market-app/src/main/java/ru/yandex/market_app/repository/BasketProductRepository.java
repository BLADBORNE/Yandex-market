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

    public Flux<ProductCount> findActiveProductCounts(Long userId) {
        return databaseClient.sql("""
                SELECT bp.product_id, bp.count
                FROM market.basket_product bp
                JOIN market.basket b ON b.id = bp.basket_id
                WHERE b.status IN ('ACTIVE', 'CHECKOUT')
                  AND b.user_id = :userId
                ORDER BY bp.product_id
                """)
            .bind("userId", userId)
            .map((row, metadata) -> new ProductCount(
                row.get("product_id", Long.class),
                row.get("count", Integer.class)
            ))
            .all();
    }

    public Mono<Integer> findActiveProductCount(Long userId, Long productId) {
        return databaseClient.sql("""
                SELECT bp.count
                FROM market.basket_product bp
                JOIN market.basket b ON b.id = bp.basket_id
                WHERE b.status IN ('ACTIVE', 'CHECKOUT')
                  AND b.user_id = :userId
                  AND bp.product_id = :productId
                """)
            .bind("userId", userId)
            .bind("productId", productId)
            .map((row, metadata) -> row.get("count", Integer.class))
            .one();
    }

    public Mono<Integer> increment(Long basketId, Long productId) {
        return databaseClient.sql("""
                INSERT INTO market.basket_product (basket_id, product_id, count)
                SELECT :basketId, :productId, 1
                FROM market.basket
                WHERE id = :basketId
                  AND status = 'ACTIVE'
                ON CONFLICT (product_id, basket_id)
                DO UPDATE SET count = market.basket_product.count + 1
                WHERE EXISTS (
                    SELECT 1
                    FROM market.basket
                    WHERE id = :basketId
                      AND status = 'ACTIVE'
                )
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
                  AND EXISTS (
                      SELECT 1
                      FROM market.basket
                      WHERE id = :basketId
                        AND status = 'ACTIVE'
                  )
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
                  AND EXISTS (
                      SELECT 1
                      FROM market.basket
                      WHERE id = :basketId
                        AND status = 'ACTIVE'
                  )
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
                  AND EXISTS (
                      SELECT 1
                      FROM market.basket
                      WHERE id = :basketId
                        AND status = 'ACTIVE'
                  )
                RETURNING 0 AS count
                """)
            .bind("basketId", basketId)
            .bind("productId", productId)
            .map((row, metadata) -> row.get("count", Integer.class))
            .one();
    }
}
