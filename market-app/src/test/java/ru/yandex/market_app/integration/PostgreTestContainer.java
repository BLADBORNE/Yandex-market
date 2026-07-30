package ru.yandex.market_app.integration;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.NoTransactionException;
import org.springframework.transaction.reactive.TransactionSynchronizationManager;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import reactor.core.publisher.Mono;
import ru.yandex.market_app.payment.PaymentGateway;
import ru.yandex.market_app.payment.PaymentReceipt;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@TestConfiguration(proxyBeanMethods = false)
public class PostgreTestContainer {

    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgresContainer() {
        return new PostgreSQLContainer<>("postgres:17-alpine");
    }

    @Bean
    @ServiceConnection(name = "redis")
    GenericContainer<?> redisContainer() {
        return new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
            .withExposedPorts(6379);
    }

    @Bean
    @Primary
    TestPaymentGateway testPaymentGateway(DatabaseClient databaseClient) {
        return new TestPaymentGateway(databaseClient);
    }

    public static final class TestPaymentGateway implements PaymentGateway {

        private static final BigDecimal DEFAULT_BALANCE = new BigDecimal("1000000.00");

        private final DatabaseClient databaseClient;
        private final AtomicReference<PaymentFunction> payment =
            new AtomicReference<>(this::successfulPayment);
        private final List<UUID> requestIds = new CopyOnWriteArrayList<>();
        private final List<UUID> accountIds = new CopyOnWriteArrayList<>();
        private final ConcurrentMap<UUID, PaymentReceipt> completedPayments =
            new ConcurrentHashMap<>();
        private final AtomicInteger debitCount = new AtomicInteger();

        private TestPaymentGateway(DatabaseClient databaseClient) {
            this.databaseClient = databaseClient;
        }

        @Override
        public Mono<BigDecimal> getBalance(UUID accountId) {
            accountIds.add(accountId);
            return Mono.just(DEFAULT_BALANCE);
        }

        @Override
        public Mono<PaymentReceipt> pay(UUID accountId, UUID requestId, BigDecimal amount) {
            accountIds.add(accountId);
            requestIds.add(requestId);
            return payment.get().apply(accountId, requestId, amount);
        }

        public void failPayment(RuntimeException exception) {
            payment.set((accountId, requestId, amount) -> Mono.error(exception));
        }

        public void loseNextPaymentResponses(int attempts, RuntimeException exception) {
            var remainingLostResponses = new AtomicInteger(attempts);
            payment.set((accountId, requestId, amount) ->
                successfulPayment(accountId, requestId, amount)
                    .flatMap(receipt ->
                        remainingLostResponses.getAndUpdate(current ->
                            Math.max(0, current - 1)
                        ) > 0
                            ? Mono.error(exception)
                            : Mono.just(receipt)));
        }

        public void succeedPayments() {
            payment.set(this::successfulPayment);
        }

        public void assertPreparedCheckoutOutsideTransaction() {
            payment.set((accountId, requestId, amount) ->
                TransactionSynchronizationManager.forCurrentTransaction()
                    .flatMap(transaction -> Mono.<PaymentReceipt>error(
                        new AssertionError("HTTP-платёж выполняется внутри DB-транзакции")
                    ))
                    .onErrorResume(NoTransactionException.class, error -> databaseClient.sql("""
                            SELECT COUNT(*) AS prepared
                            FROM market."order" o
                            JOIN market.basket b ON b.id = o.basket_id
                            WHERE o.payment_account_id = :accountId
                              AND o.payment_request_id = :requestId
                              AND o.sum = :amount
                              AND o.status = 'PENDING'
                              AND b.status = 'CHECKOUT'
                            """)
                        .bind("accountId", accountId)
                        .bind("requestId", requestId)
                        .bind("amount", amount)
                        .map((row, metadata) -> row.get("prepared", Long.class))
                        .one()
                        .filter(prepared -> prepared == 1L)
                        .switchIfEmpty(Mono.error(new AssertionError(
                            "Ожидающий заказ не был зафиксирован до HTTP-платежа"
                        )))
                        .then(successfulPayment(accountId, requestId, amount))));
        }

        public List<UUID> requestIds() {
            return List.copyOf(requestIds);
        }

        public List<UUID> accountIds() {
            return List.copyOf(accountIds);
        }

        public int debitCount() {
            return debitCount.get();
        }

        public void reset() {
            payment.set(this::successfulPayment);
            requestIds.clear();
            accountIds.clear();
            completedPayments.clear();
            debitCount.set(0);
        }

        private Mono<PaymentReceipt> successfulPayment(UUID accountId, UUID requestId, BigDecimal amount) {
            return Mono.fromSupplier(() -> completedPayments.computeIfAbsent(
                requestId,
                ignoredRequestId -> {
                    debitCount.incrementAndGet();
                    return new PaymentReceipt(
                        requestId,
                        amount,
                        DEFAULT_BALANCE.subtract(amount)
                    );
                }
            ));
        }

        @FunctionalInterface
        private interface PaymentFunction {

            Mono<PaymentReceipt> apply(UUID accountId, UUID requestId, BigDecimal amount);
        }
    }
}
