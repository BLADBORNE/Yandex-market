package ru.yandex.market_app.repository;

import io.r2dbc.spi.Row;
import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.yandex.market_app.model.Order;

import java.math.BigDecimal;

@Repository
@RequiredArgsConstructor
public class OrderRepository {

    private final DatabaseClient databaseClient;

    public Flux<Order> findAll() {
        return databaseClient.sql("""
                SELECT id, basket_id, sum
                FROM market."order"
                ORDER BY id
                """)
            .map((row, metadata) -> toOrder(row))
            .all();
    }

    public Mono<Order> findById(Long id) {
        return databaseClient.sql("""
                SELECT id, basket_id, sum
                FROM market."order"
                WHERE id = :id
                """)
            .bind("id", id)
            .map((row, metadata) -> toOrder(row))
            .one();
    }

    public Mono<Long> findIdByBasketId(Long basketId) {
        return databaseClient.sql("""
                SELECT id
                FROM market."order"
                WHERE basket_id = :basketId
                """)
            .bind("basketId", basketId)
            .map((row, metadata) -> row.get("id", Long.class))
            .one();
    }

    public Mono<Long> create(Long basketId, BigDecimal sum) {
        return databaseClient.sql("""
                INSERT INTO market."order" (basket_id, sum)
                VALUES (:basketId, :sum)
                RETURNING id
                """)
            .bind("basketId", basketId)
            .bind("sum", sum)
            .map((row, metadata) -> row.get("id", Long.class))
            .one();
    }

    public Mono<Long> createItemsSnapshot(Long orderId, Long basketId) {
        return databaseClient.sql("""
                INSERT INTO market.order_item (order_id, product_id, title, price, count)
                SELECT :orderId,
                       p.id,
                       p.title,
                       p.price,
                       bp.count
                FROM market.basket_product bp
                JOIN market.product p ON p.id = bp.product_id
                WHERE bp.basket_id = :basketId
                """)
            .bind("orderId", orderId)
            .bind("basketId", basketId)
            .fetch()
            .rowsUpdated();
    }

    private Order toOrder(Row row) {
        return Order.builder()
            .id(row.get("id", Long.class))
            .basketId(row.get("basket_id", Long.class))
            .sum(row.get("sum", BigDecimal.class))
            .build();
    }
}
