package ru.yandex.market_app.repository;

import io.r2dbc.spi.Row;
import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;
import ru.yandex.market_app.dto.BasketDto;
import ru.yandex.market_app.model.Basket;

import java.math.BigDecimal;

@Repository
@RequiredArgsConstructor
public class BasketRepository {

    private static final String OPEN_BASKET = """
        SELECT id, user_id, status
        FROM market.basket
        WHERE status IN ('ACTIVE', 'CHECKOUT')
          AND user_id = :userId
        LIMIT 1
        """;

    private final DatabaseClient databaseClient;

    public Mono<Basket> findOpenBasket(Long userId) {
        return databaseClient.sql(OPEN_BASKET)
            .bind("userId", userId)
            .map((row, metadata) -> toBasket(row))
            .one();
    }

    public Mono<Basket> findActiveBasketForUpdate(Long userId) {
        return databaseClient.sql("""
                SELECT id, user_id, status
                FROM market.basket
                WHERE status = 'ACTIVE'
                  AND user_id = :userId
                LIMIT 1
                FOR UPDATE
                """)
            .bind("userId", userId)
            .map((row, metadata) -> toBasket(row))
            .one();
    }

    public Mono<Basket> findCheckoutBasketByIdForUpdate(Long userId, Long basketId) {
        return databaseClient.sql("""
                SELECT id, user_id, status
                FROM market.basket
                WHERE id = :basketId
                  AND user_id = :userId
                  AND status = 'CHECKOUT'
                FOR UPDATE
                """)
            .bind("basketId", basketId)
            .bind("userId", userId)
            .map((row, metadata) -> toBasket(row))
            .one();
    }

    public Mono<Basket> getOrCreateActiveBasket(Long userId) {
        return databaseClient.sql("""
                INSERT INTO market.basket (user_id, status)
                VALUES (:userId, 'ACTIVE')
                ON CONFLICT (user_id) WHERE status IN ('ACTIVE', 'CHECKOUT')
                DO UPDATE SET status = EXCLUDED.status
                WHERE market.basket.status = 'ACTIVE'
                RETURNING id, user_id, status
                """)
            .bind("userId", userId)
            .map((row, metadata) -> toBasket(row))
            .one();
    }

    public Mono<BasketDto> findActiveBasketWithTotal(Long userId) {
        return databaseClient.sql("""
                SELECT b.id,
                       SUM(p.price * bp.count) AS total_sum
                FROM market.basket b
                JOIN market.basket_product bp ON bp.basket_id = b.id
                JOIN market.product p ON p.id = bp.product_id
                WHERE b.status IN ('ACTIVE', 'CHECKOUT')
                  AND b.user_id = :userId
                GROUP BY b.id
                """)
            .bind("userId", userId)
            .map((row, metadata) -> new BasketDto(
                row.get("id", Long.class),
                row.get("total_sum", BigDecimal.class)
            ))
            .one();
    }

    public Mono<BigDecimal> calculateBasketTotal(Long userId, Long basketId) {
        return databaseClient.sql("""
                SELECT SUM(p.price * bp.count) AS total_sum
                FROM market.basket_product bp
                JOIN market.basket b ON b.id = bp.basket_id
                JOIN market.product p ON p.id = bp.product_id
                WHERE bp.basket_id = :basketId
                  AND b.user_id = :userId
                HAVING COUNT(*) > 0
                """)
            .bind("basketId", basketId)
            .bind("userId", userId)
            .map((row, metadata) -> row.get("total_sum", BigDecimal.class))
            .one();
    }

    public Mono<Basket> findById(Long userId, Long id) {
        return databaseClient.sql("""
                SELECT id, user_id, status
                FROM market.basket
                WHERE id = :id
                  AND user_id = :userId
                """)
            .bind("id", id)
            .bind("userId", userId)
            .map((row, metadata) -> toBasket(row))
            .one();
    }

    public Mono<Boolean> closeBasket(Long userId, Long basketId) {
        return databaseClient.sql("""
                UPDATE market.basket
                SET status = 'CLOSED'
                WHERE id = :basketId
                  AND user_id = :userId
                  AND status = 'ACTIVE'
                """)
            .bind("basketId", basketId)
            .bind("userId", userId)
            .fetch()
            .rowsUpdated()
            .map(updated -> updated == 1L);
    }

    public Mono<Boolean> closeCheckout(Long userId, Long basketId) {
        return databaseClient.sql("""
                UPDATE market.basket
                SET status = 'CLOSED'
                WHERE id = :basketId
                  AND user_id = :userId
                  AND status = 'CHECKOUT'
                """)
            .bind("basketId", basketId)
            .bind("userId", userId)
            .fetch()
            .rowsUpdated()
            .map(updated -> updated == 1L);
    }

    public Mono<Boolean> markCheckout(Long userId, Long basketId) {
        return databaseClient.sql("""
                UPDATE market.basket
                SET status = 'CHECKOUT'
                WHERE id = :basketId
                  AND user_id = :userId
                  AND status = 'ACTIVE'
                """)
            .bind("basketId", basketId)
            .bind("userId", userId)
            .fetch()
            .rowsUpdated()
            .map(updated -> updated == 1L);
    }

    public Mono<Boolean> restoreActive(Long userId, Long basketId) {
        return databaseClient.sql("""
                UPDATE market.basket
                SET status = 'ACTIVE'
                WHERE id = :basketId
                  AND user_id = :userId
                  AND status = 'CHECKOUT'
                """)
            .bind("basketId", basketId)
            .bind("userId", userId)
            .fetch()
            .rowsUpdated()
            .map(updated -> updated == 1L);
    }

    private Basket toBasket(Row row) {
        return Basket.builder()
            .id(row.get("id", Long.class))
            .userId(row.get("user_id", Long.class))
            .status(Basket.Status.valueOf(row.get("status", String.class)))
            .build();
    }
}
