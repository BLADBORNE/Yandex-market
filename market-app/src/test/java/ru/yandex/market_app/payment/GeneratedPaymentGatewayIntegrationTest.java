package ru.yandex.market_app.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.server.HttpServer;
import reactor.test.StepVerifier;
import ru.yandex.market_app.configuration.PaymentClientProperties;
import ru.yandex.market_app.payment.generated.api.PaymentsApi;
import ru.yandex.market_app.payment.generated.invoker.ApiClient;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeneratedPaymentGatewayIntegrationTest {

    private DisposableServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.disposeNow();
        }
    }

    @Test
    void shouldUseGeneratedClientForBalanceJson() {
        server = HttpServer.create()
            .host("127.0.0.1")
            .port(0)
            .route(routes -> routes.get("/api/v1/balance", (request, response) -> response
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .sendString(Mono.just("{\"balance\":5000.25}"))))
            .bindNow();

        StepVerifier.create(gateway().getBalance())
            .expectNext(new BigDecimal("5000.25"))
            .verifyComplete();
    }

    @Test
    void shouldSendGeneratedPaymentJsonAndReadResponse() {
        UUID requestId = UUID.randomUUID();
        var receivedBody = new AtomicReference<String>();
        server = HttpServer.create()
            .host("127.0.0.1")
            .port(0)
            .route(routes -> routes.post("/api/v1/payment", (request, response) -> request
                .receive()
                .aggregate()
                .asString()
                .flatMap(body -> {
                    receivedBody.set(body);
                    return response
                        .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                        .sendString(Mono.just("""
                            {"requestId":"%s","amount":125.50,"remainingBalance":874.50}
                            """.formatted(requestId)))
                        .then();
                })))
            .bindNow();

        StepVerifier.create(gateway().pay(requestId, new BigDecimal("125.50")))
            .assertNext(receipt -> {
                assertEquals(requestId, receipt.requestId());
                assertEquals(0, new BigDecimal("125.50").compareTo(receipt.amount()));
                assertEquals(0, new BigDecimal("874.50").compareTo(receipt.remainingBalance()));
            })
            .verifyComplete();

        assertTrue(receivedBody.get().contains("\"requestId\":\"" + requestId + "\""));
        assertTrue(receivedBody.get().contains("\"amount\":125.50"));
    }

    @Test
    void shouldMapConflictJsonToInsufficientFunds() {
        server = HttpServer.create()
            .host("127.0.0.1")
            .port(0)
            .route(routes -> routes.post("/api/v1/payment", (request, response) -> response
                .status(409)
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .sendString(Mono.just("""
                    {
                      "code":"INSUFFICIENT_FUNDS",
                      "message":"Недостаточно средств",
                      "availableBalance":10.00,
                      "requiredAmount":20.00
                    }
                    """))))
            .bindNow();

        StepVerifier.create(gateway().pay(UUID.randomUUID(), new BigDecimal("20.00")))
            .expectErrorSatisfies(error -> {
                var exception = (InsufficientFundsException) error;
                assertEquals(0, new BigDecimal("10.00").compareTo(exception.getAvailableBalance()));
                assertEquals(0, new BigDecimal("20.00").compareTo(exception.getRequiredAmount()));
            })
            .verify();
    }

    @Test
    void shouldMapConnectionFailureToUnavailable() {
        server = HttpServer.create()
            .host("127.0.0.1")
            .port(0)
            .bindNow();
        int stoppedPort = server.port();
        server.disposeNow();
        server = null;

        StepVerifier.create(gateway(stoppedPort).getBalance())
            .expectError(PaymentServiceUnavailableException.class)
            .verify();
    }

    @Test
    void shouldMapServerErrorAndTimeoutToUnavailable() {
        server = HttpServer.create()
            .host("127.0.0.1")
            .port(0)
            .route(routes -> {
                routes.get("/api/v1/balance", (request, response) -> response
                    .status(500)
                    .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .sendString(Mono.just("""
                        {"code":"INTERNAL_ERROR","message":"failure"}
                        """)));
                routes.post("/api/v1/payment", (request, response) -> response
                    .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .sendString(Mono.delay(Duration.ofSeconds(1))
                        .thenReturn("""
                            {
                              "requestId":"00000000-0000-0000-0000-000000000001",
                              "amount":1.00,
                              "remainingBalance":10.00
                            }
                            """)));
            })
            .bindNow();

        StepVerifier.create(gateway(Duration.ofMillis(100)).getBalance())
            .expectError(PaymentServiceUnavailableException.class)
            .verify();

        StepVerifier.create(gateway(Duration.ofMillis(100))
                .pay(UUID.randomUUID(), BigDecimal.ONE))
            .expectError(PaymentServiceUnavailableException.class)
            .verify();
    }

    @Test
    void shouldMapIdempotencyConflictToRejectedPayment() {
        server = HttpServer.create()
            .host("127.0.0.1")
            .port(0)
            .route(routes -> routes.post("/api/v1/payment", (request, response) -> response
                .status(409)
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .sendString(Mono.just("""
                    {
                      "code":"IDEMPOTENCY_CONFLICT",
                      "message":"requestId already used"
                    }
                    """))))
            .bindNow();

        StepVerifier.create(gateway().pay(UUID.randomUUID(), BigDecimal.ONE))
            .expectErrorMatches(error -> error instanceof PaymentRejectedException
                && error.getMessage().contains("requestId"))
            .verify();
    }

    @Test
    void shouldRejectMismatchedSuccessfulPaymentResponse() {
        UUID expectedRequestId = UUID.randomUUID();
        server = HttpServer.create()
            .host("127.0.0.1")
            .port(0)
            .route(routes -> routes.post("/api/v1/payment", (request, response) -> response
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .sendString(Mono.just("""
                    {
                      "requestId":"00000000-0000-0000-0000-000000000001",
                      "amount":2.00,
                      "remainingBalance":10.00
                    }
                    """))))
            .bindNow();

        StepVerifier.create(gateway().pay(expectedRequestId, BigDecimal.ONE))
            .expectErrorMatches(error -> error instanceof PaymentServiceUnavailableException
                && error.getMessage().contains("некорректный ответ"))
            .verify();
    }

    private GeneratedPaymentGateway gateway() {
        return gateway(server.port(), Duration.ofSeconds(1));
    }

    private GeneratedPaymentGateway gateway(int port) {
        return gateway(port, Duration.ofSeconds(1));
    }

    private GeneratedPaymentGateway gateway(Duration responseTimeout) {
        return gateway(server.port(), responseTimeout);
    }

    private GeneratedPaymentGateway gateway(int port, Duration responseTimeout) {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        var properties = new PaymentClientProperties(
            URI.create("http://127.0.0.1:" + port),
            Duration.ofMillis(300),
            responseTimeout
        );
        WebClient webClient = ApiClient.buildWebClientBuilder(objectMapper)
            .clientConnector(new org.springframework.http.client.reactive.ReactorClientHttpConnector(
                HttpClient.create().responseTimeout(properties.responseTimeout())
            ))
            .build();
        ApiClient apiClient = new ApiClient(
            webClient,
            objectMapper,
            ApiClient.createDefaultDateFormat()
        ).setBasePath(properties.baseUrl().toString());

        return new GeneratedPaymentGateway(
            new PaymentsApi(apiClient),
            properties,
            objectMapper
        );
    }
}
