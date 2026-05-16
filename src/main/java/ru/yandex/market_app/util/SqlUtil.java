package ru.yandex.market_app.util;

import lombok.experimental.UtilityClass;

@UtilityClass
public class SqlUtil {

    public final String GET_PRODUCTS_BY_SEARCH = """
       SELECT p.id,
              p.title,
              p.description,
              p.imgPath,
              p.price,
              COALESCE((SELECT bp.count
                        FROM BasketProduct bp
                                 JOIN bp.basket b
                        WHERE bp.id.productId = p.id
                          AND b.status = 'ACTIVE'), 0)
       FROM Product p
       WHERE (:search IS NULL
           OR LOWER(p.title) LIKE CONCAT('%', LOWER(:search), '%')
           OR LOWER(p.description) LIKE CONCAT('%', LOWER(:search), '%'))
       """;

    public final String GET_ITEM = """
         SELECT NEW ru.yandex.market_app.dto.ProductResultDto(
                p.id,
                p.title,
                p.description,
                p.imgPath,
                p.price,
                (SELECT COALESCE(bp.count, 0) from BasketProduct bp JOIN bp.basket b WHERE bp.id.productId = p.id AND b.status = 'ACTIVE') as product_count
            )
            FROM Product p
            WHERE p.id = :id
        """;

    public final String GET_PRODUCT_CART = """
        WITH basket_sum AS (SELECT COALESCE(SUM(p.price * bp.count), 0) total
                            FROM market.basket b
                                     JOIN market.basket_product bp ON bp.basket_id = b.id
                                     JOIN market.product p ON p.id = bp.product_id
                            WHERE b.status = 'ACTIVE')
        SELECT p.id,
               p.title,
               p.description,
               p.img_path,
               p.price,
               (SELECT bp.count
                from market.basket_product bp
                         JOIN market.basket b ON b.id = bp.basket_id
                WHERE bp.product_id = p.id
                  AND b.status = 'ACTIVE') as product_count,
               basket_sum.total
        FROM market.product p
                 JOIN market.basket_product bp on bp.product_id = p.id
                 JOIN market.basket b ON b.id = bp.basket_id
                 CROSS JOIN basket_sum
        WHERE b.status = 'ACTIVE'
        ORDER BY bp.count, p.title
        """;

    public final String FIND_EAGER_BASKET_WITH_ENTITIES_ID = """
         SELECT b
         FROM Basket b
         JOIN FETCH b.basketProducts
         WHERE b.status = :status
        """;

    public final String FIND_ACTIVE_BASKET_WITH_TOTAL_SUM = """
        SELECT new ru.yandex.market_app.dto.BasketDto(b.id, COALESCE(SUM(p.price * bp.count), 0))
        FROM Basket b
        JOIN b.basketProducts bp
        JOIN bp.product p
        WHERE b.status = :status
        GROUP BY b.id
        """;

    public final String CLOSE_ACTIVE_BASKET = "UPDATE Basket b SET b.status = 'CLOSED' WHERE b.status = 'ACTIVE'";
}
