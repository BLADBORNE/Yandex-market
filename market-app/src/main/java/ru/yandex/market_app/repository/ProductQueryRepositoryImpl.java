package ru.yandex.market_app.repository;

import io.r2dbc.spi.Row;
import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.yandex.market_app.dto.ItemDto;
import ru.yandex.market_app.dto.ProductCartResultDto;
import ru.yandex.market_app.dto.ProductResultDto;
import ru.yandex.market_app.util.ProductPageableUtil.ProductSort;

import java.math.BigDecimal;

@RequiredArgsConstructor
public class ProductQueryRepositoryImpl implements ProductQueryRepository {

    private static final String CATALOG_QUERY = """
        WITH active_basket AS (
            SELECT id
            FROM market.basket
            WHERE status = 'ACTIVE'
            LIMIT 1
        )
        SELECT p.id,
               p.title,
               p.description,
               p.img_path,
               p.price,
               COALESCE(bp.count, 0) AS product_count
        FROM market.product p
        LEFT JOIN active_basket b ON TRUE
        LEFT JOIN market.basket_product bp
               ON bp.basket_id = b.id AND bp.product_id = p.id
        WHERE (:search = ''
               OR LOWER(p.title) LIKE CONCAT('%', LOWER(:search), '%')
               OR LOWER(p.description) LIKE CONCAT('%', LOWER(:search), '%'))
        ORDER BY CASE WHEN :sort = 'ALPHA' THEN LOWER(p.title) END,
                 CASE WHEN :sort = 'PRICE' THEN p.price END,
                 p.id
        LIMIT :limit OFFSET :offset
        """;

    private static final String ITEM_QUERY = """
        SELECT p.id,
               p.title,
               p.description,
               p.img_path,
               p.price,
               COALESCE((
                   SELECT bp.count
                   FROM market.basket_product bp
                   JOIN market.basket b ON b.id = bp.basket_id
                   WHERE bp.product_id = p.id
                     AND b.status = 'ACTIVE'
               ), 0) AS product_count
        FROM market.product p
        WHERE p.id = :id
        """;

    private static final String CART_QUERY = """
        SELECT p.id,
               p.title,
               p.description,
               p.img_path,
               p.price,
               bp.count AS product_count
        FROM market.basket b
        JOIN market.basket_product bp ON bp.basket_id = b.id
        JOIN market.product p ON p.id = bp.product_id
        WHERE b.status = 'ACTIVE'
        ORDER BY p.title, p.id
        """;

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
    public Flux<ProductResultDto> findCatalogPage(String search, ProductSort sort, int limit, long offset) {
        return databaseClient.sql(CATALOG_QUERY)
            .bind("search", search)
            .bind("sort", sort.name())
            .bind("limit", limit)
            .bind("offset", offset)
            .map((row, metadata) -> toProductResult(row))
            .all();
    }

    @Override
    public Mono<ProductResultDto> findCatalogItem(Long id) {
        return databaseClient.sql(ITEM_QUERY)
            .bind("id", id)
            .map((row, metadata) -> toProductResult(row))
            .one();
    }

    @Override
    public Flux<ProductCartResultDto> findActiveCartItems() {
        return databaseClient.sql(CART_QUERY)
            .map((row, metadata) -> new ProductCartResultDto(
                row.get("id", Long.class),
                row.get("title", String.class),
                row.get("description", String.class),
                row.get("img_path", String.class),
                row.get("price", BigDecimal.class),
                row.get("product_count", Integer.class)
            ))
            .all();
    }

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

    private ProductResultDto toProductResult(Row row) {
        return ProductResultDto.builder()
            .id(row.get("id", Long.class))
            .title(row.get("title", String.class))
            .description(row.get("description", String.class))
            .imgPath(row.get("img_path", String.class))
            .price(row.get("price", BigDecimal.class))
            .count(row.get("product_count", Integer.class))
            .build();
    }
}
