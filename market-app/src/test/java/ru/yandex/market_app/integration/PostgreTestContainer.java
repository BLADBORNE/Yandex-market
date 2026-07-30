package ru.yandex.market_app.integration;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import reactor.core.publisher.Mono;
import ru.yandex.market_app.payment.PaymentGateway;
import ru.yandex.market_app.payment.PaymentReceipt;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;

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
    TestPaymentGateway testPaymentGateway() {
        return new TestPaymentGateway();
    }

    public static final class TestPaymentGateway implements PaymentGateway {

        private static final BigDecimal DEFAULT_BALANCE = new BigDecimal("1000000.00");

        private final AtomicReference<BiFunction<java.util.UUID, BigDecimal, Mono<PaymentReceipt>>> payment =
            new AtomicReference<>(this::successfulPayment);

        @Override
        public Mono<BigDecimal> getBalance() {
            return Mono.just(DEFAULT_BALANCE);
        }

        @Override
        public Mono<PaymentReceipt> pay(java.util.UUID requestId, BigDecimal amount) {
            return payment.get().apply(requestId, amount);
        }

        public void failPayment(RuntimeException exception) {
            payment.set((requestId, amount) -> Mono.error(exception));
        }

        public void reset() {
            payment.set(this::successfulPayment);
        }

        private Mono<PaymentReceipt> successfulPayment(java.util.UUID requestId, BigDecimal amount) {
            return Mono.just(new PaymentReceipt(
                requestId,
                amount,
                DEFAULT_BALANCE.subtract(amount)
            ));
        }
    }
}
