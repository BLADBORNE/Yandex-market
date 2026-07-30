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

    private static final String ACTIVE_BASKET = """
        SELECT id, status
        FROM market.basket
        WHERE status = 'ACTIVE'
        LIMIT 1
        """;

    private final DatabaseClient databaseClient;

    public Mono<Basket> findActiveBasket() {
        return databaseClient.sql(ACTIVE_BASKET)
            .map((row, metadata) -> toBasket(row))
            .one();
    }

    public Mono<Basket> findActiveBasketForUpdate() {
        return databaseClient.sql(ACTIVE_BASKET + " FOR UPDATE")
            .map((row, metadata) -> toBasket(row))
            .one();
    }

    public Mono<Basket> findActiveBasketByIdForUpdate(Long basketId) {
        return databaseClient.sql("""
                SELECT id, status
                FROM market.basket
                WHERE id = :basketId
                  AND status = 'ACTIVE'
                FOR UPDATE
                """)
            .bind("basketId", basketId)
            .map((row, metadata) -> toBasket(row))
            .one();
    }

    public Mono<Basket> getOrCreateActiveBasket() {
        return databaseClient.sql("""
                INSERT INTO market.basket (status)
                VALUES ('ACTIVE')
                ON CONFLICT (status) WHERE status = 'ACTIVE'
                DO UPDATE SET status = EXCLUDED.status
                RETURNING id, status
                """)
            .map((row, metadata) -> toBasket(row))
            .one();
    }

    public Mono<BasketDto> findActiveBasketWithTotal() {
        return databaseClient.sql("""
                SELECT b.id,
                       SUM(p.price * bp.count) AS total_sum
                FROM market.basket b
                JOIN market.basket_product bp ON bp.basket_id = b.id
                JOIN market.product p ON p.id = bp.product_id
                WHERE b.status = 'ACTIVE'
                GROUP BY b.id
                """)
            .map((row, metadata) -> new BasketDto(
                row.get("id", Long.class),
                row.get("total_sum", BigDecimal.class)
            ))
            .one();
    }

    public Mono<BigDecimal> calculateBasketTotal(Long basketId) {
        return databaseClient.sql("""
                SELECT SUM(p.price * bp.count) AS total_sum
                FROM market.basket_product bp
                JOIN market.product p ON p.id = bp.product_id
                WHERE bp.basket_id = :basketId
                HAVING COUNT(*) > 0
                """)
            .bind("basketId", basketId)
            .map((row, metadata) -> row.get("total_sum", BigDecimal.class))
            .one();
    }

    public Mono<Basket> findById(Long id) {
        return databaseClient.sql("""
                SELECT id, status
                FROM market.basket
                WHERE id = :id
                """)
            .bind("id", id)
            .map((row, metadata) -> toBasket(row))
            .one();
    }

    public Mono<Boolean> closeBasket(Long basketId) {
        return databaseClient.sql("""
                UPDATE market.basket
                SET status = 'CLOSED'
                WHERE id = :basketId
                  AND status = 'ACTIVE'
                """)
            .bind("basketId", basketId)
            .fetch()
            .rowsUpdated()
            .map(updated -> updated == 1L);
    }

    private Basket toBasket(Row row) {
        return Basket.builder()
            .id(row.get("id", Long.class))
            .status(Basket.Status.valueOf(row.get("status", String.class)))
            .build();
    }
}
