package ru.yandex.market_app.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Flux;
import ru.yandex.market_app.dto.ItemDto;

import java.math.BigDecimal;

@RequiredArgsConstructor
public class ProductQueryRepositoryImpl implements ProductQueryRepository {

    private static final String ORDER_ITEMS_QUERY = """
        SELECT product_id AS id,
               title,
               price,
               count AS product_count
        FROM market.order_item
        WHERE order_id = :orderId
        ORDER BY title, product_id
        """;

    private final DatabaseClient databaseClient;

    @Override
    public Flux<ItemDto> findOrderItems(Long orderId) {
        return databaseClient.sql(ORDER_ITEMS_QUERY)
            .bind("orderId", orderId)
            .map((row, metadata) -> ItemDto.builder()
                .id(row.get("id", Long.class))
                .title(row.get("title", String.class))
                .price(row.get("price", BigDecimal.class))
                .count(row.get("product_count", Integer.class))
                .build())
            .all();
    }

}
