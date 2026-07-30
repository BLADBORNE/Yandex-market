package ru.yandex.market_app.repository;

import io.r2dbc.spi.Row;
import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.yandex.market_app.model.Order;

import java.math.BigDecimal;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class OrderRepository {

    private final DatabaseClient databaseClient;

    public Flux<Order> findAllByUserId(Long userId) {
        return databaseClient.sql("""
                SELECT id,
                       basket_id,
                       user_id,
                       sum,
                       status,
                       payment_account_id,
                       payment_request_id,
                       payment_attempted
                FROM market."order"
                WHERE user_id = :userId
                  AND status = 'COMPLETED'
                ORDER BY id
                """)
            .bind("userId", userId)
            .map((row, metadata) -> toOrder(row))
            .all();
    }

    public Mono<Order> findByIdAndUserId(Long id, Long userId) {
        return databaseClient.sql("""
                SELECT id,
                       basket_id,
                       user_id,
                       sum,
                       status,
                       payment_account_id,
                       payment_request_id,
                       payment_attempted
                FROM market."order"
                WHERE id = :id
                  AND user_id = :userId
                  AND status = 'COMPLETED'
                """)
            .bind("id", id)
            .bind("userId", userId)
            .map((row, metadata) -> toOrder(row))
            .one();
    }

    public Mono<Order> findPendingByUserId(Long userId) {
        return databaseClient.sql("""
                SELECT id,
                       basket_id,
                       user_id,
                       sum,
                       status,
                       payment_account_id,
                       payment_request_id,
                       payment_attempted
                FROM market."order"
                WHERE user_id = :userId
                  AND status = 'PENDING'
                """)
            .bind("userId", userId)
            .map((row, metadata) -> toOrder(row))
            .one();
    }

    public Mono<Order> createPending(
        Long basketId,
        Long userId,
        BigDecimal sum,
        UUID paymentAccountId,
        UUID paymentRequestId
    ) {
        return databaseClient.sql("""
                INSERT INTO market."order" AS existing (
                    basket_id,
                    user_id,
                    sum,
                    status,
                    payment_account_id,
                    payment_request_id
                )
                VALUES (
                    :basketId,
                    :userId,
                    :sum,
                    'PENDING',
                    :paymentAccountId,
                    :paymentRequestId
                )
                ON CONFLICT (basket_id)
                DO UPDATE SET basket_id = EXCLUDED.basket_id
                WHERE existing.status = 'PENDING'
                  AND existing.user_id = EXCLUDED.user_id
                RETURNING id,
                          basket_id,
                          user_id,
                          sum,
                          status,
                          payment_account_id,
                          payment_request_id,
                          payment_attempted
                """)
            .bind("basketId", basketId)
            .bind("userId", userId)
            .bind("sum", sum)
            .bind("paymentAccountId", paymentAccountId)
            .bind("paymentRequestId", paymentRequestId)
            .map((row, metadata) -> toOrder(row))
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
                ON CONFLICT (order_id, product_id) DO NOTHING
                """)
            .bind("orderId", orderId)
            .bind("basketId", basketId)
            .fetch()
            .rowsUpdated();
    }

    public Mono<Long> countItemsSnapshot(Long orderId) {
        return databaseClient.sql("""
                SELECT COUNT(*) AS item_count
                FROM market.order_item
                WHERE order_id = :orderId
                """)
            .bind("orderId", orderId)
            .map((row, metadata) -> row.get("item_count", Long.class))
            .one();
    }

    public Mono<BigDecimal> updatePendingSumFromSnapshot(Long orderId, Long userId) {
        return databaseClient.sql("""
                UPDATE market."order" o
                SET sum = snapshot.total
                FROM (
                    SELECT SUM(price * count) AS total
                    FROM market.order_item
                    WHERE order_id = :orderId
                    HAVING COUNT(*) > 0
                ) snapshot
                WHERE o.id = :orderId
                  AND o.user_id = :userId
                  AND o.status = 'PENDING'
                RETURNING o.sum
                """)
            .bind("orderId", orderId)
            .bind("userId", userId)
            .map((row, metadata) -> row.get("sum", BigDecimal.class))
            .one();
    }

    public Mono<PaymentClaim> claimPayment(
        Long orderId,
        Long userId,
        UUID attemptId,
        long leaseMillis
    ) {
        return databaseClient.sql("""
                WITH claimable AS (
                    SELECT id,
                           payment_attempted AS previously_attempted
                    FROM market."order"
                    WHERE id = :orderId
                      AND user_id = :userId
                      AND status = 'PENDING'
                      AND (
                          payment_attempt_id IS NULL
                          OR payment_attempt_started_at
                              < CURRENT_TIMESTAMP - (:leaseMillis * INTERVAL '1 millisecond')
                      )
                    FOR UPDATE
                )
                UPDATE market."order" claimed
                SET payment_attempt_id = :attemptId,
                    payment_attempt_started_at = CURRENT_TIMESTAMP,
                    payment_attempted = TRUE
                FROM claimable
                WHERE claimed.id = claimable.id
                RETURNING claimable.previously_attempted
                """)
            .bind("orderId", orderId)
            .bind("userId", userId)
            .bind("attemptId", attemptId)
            .bind("leaseMillis", leaseMillis)
            .map((row, metadata) -> new PaymentClaim(Boolean.TRUE.equals(
                row.get("previously_attempted", Boolean.class)
            )))
            .one();
    }

    public Mono<Boolean> releasePaymentClaim(Long orderId, Long userId, UUID attemptId) {
        return databaseClient.sql("""
                UPDATE market."order"
                SET payment_attempt_id = NULL,
                    payment_attempt_started_at = NULL
                WHERE id = :orderId
                  AND user_id = :userId
                  AND status = 'PENDING'
                  AND payment_attempt_id = :attemptId
                """)
            .bind("orderId", orderId)
            .bind("userId", userId)
            .bind("attemptId", attemptId)
            .fetch()
            .rowsUpdated()
            .map(updated -> updated == 1L);
    }

    public Mono<Long> markCompleted(Long orderId, Long userId, UUID attemptId) {
        return databaseClient.sql("""
                UPDATE market."order"
                SET status = 'COMPLETED',
                    payment_attempt_id = NULL,
                    payment_attempt_started_at = NULL
                WHERE id = :orderId
                  AND user_id = :userId
                  AND status = 'PENDING'
                  AND payment_attempt_id = :attemptId
                RETURNING id
                """)
            .bind("orderId", orderId)
            .bind("userId", userId)
            .bind("attemptId", attemptId)
            .map((row, metadata) -> row.get("id", Long.class))
            .one();
    }

    public Mono<Long> findCompletedId(Long orderId, Long userId) {
        return databaseClient.sql("""
                SELECT id
                FROM market."order"
                WHERE id = :orderId
                  AND user_id = :userId
                  AND status = 'COMPLETED'
                """)
            .bind("orderId", orderId)
            .bind("userId", userId)
            .map((row, metadata) -> row.get("id", Long.class))
            .one();
    }

    public Mono<Boolean> deletePending(Long orderId, Long userId, UUID attemptId) {
        return databaseClient.sql("""
                DELETE FROM market."order"
                WHERE id = :orderId
                  AND user_id = :userId
                  AND status = 'PENDING'
                  AND payment_attempt_id = :attemptId
                """)
            .bind("orderId", orderId)
            .bind("userId", userId)
            .bind("attemptId", attemptId)
            .fetch()
            .rowsUpdated()
            .map(updated -> updated == 1L);
    }

    private Order toOrder(Row row) {
        return Order.builder()
            .id(row.get("id", Long.class))
            .basketId(row.get("basket_id", Long.class))
            .userId(row.get("user_id", Long.class))
            .sum(row.get("sum", BigDecimal.class))
            .status(Order.Status.valueOf(row.get("status", String.class)))
            .paymentAccountId(row.get("payment_account_id", UUID.class))
            .paymentRequestId(row.get("payment_request_id", UUID.class))
            .paymentAttempted(Boolean.TRUE.equals(
                row.get("payment_attempted", Boolean.class)
            ))
            .build();
    }

    public record PaymentClaim(boolean previouslyAttempted) {
    }
}
