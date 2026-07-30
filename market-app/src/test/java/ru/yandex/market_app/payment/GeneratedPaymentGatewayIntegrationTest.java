package ru.yandex.market_app.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.client.InMemoryReactiveOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.server.HttpServer;
import reactor.test.StepVerifier;
import ru.yandex.market_app.configuration.PaymentClientProperties;
import ru.yandex.market_app.configuration.PaymentClientConfiguration;
import ru.yandex.market_app.payment.generated.api.PaymentsApi;
import ru.yandex.market_app.payment.generated.invoker.ApiClient;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeneratedPaymentGatewayIntegrationTest {

    private static final UUID CUSTOMER_ID = UUID.fromString(
        "40000000-0000-0000-0000-000000000001"
    );

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

        StepVerifier.create(gateway().getBalance(CUSTOMER_ID))
            .expectNext(new BigDecimal("5000.25"))
            .verifyComplete();
    }

    @Test
    void shouldObtainCacheAndSendClientCredentialsToken() {
        var tokenRequests = new AtomicInteger();
        var tokenAuthorizationHeader = new AtomicReference<String>();
        var tokenRequestBody = new AtomicReference<String>();
        var authorizationHeader = new AtomicReference<String>();
        server = HttpServer.create()
            .host("127.0.0.1")
            .port(0)
            .route(routes -> {
                routes.post("/oauth/token", (request, response) -> {
                    tokenRequests.incrementAndGet();
                    tokenAuthorizationHeader.set(request.requestHeaders().get("Authorization"));
                    return request.receive()
                        .aggregate()
                        .asString()
                        .flatMap(body -> {
                            tokenRequestBody.set(body);
                            return response
                                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                                .sendString(Mono.just("""
                                    {
                                      "access_token":"payment-access-token",
                                      "token_type":"Bearer",
                                      "expires_in":300,
                                      "scope":"payment.read payment.write"
                                    }
                                    """))
                                .then();
                        });
                });
                routes.get("/api/v1/balance", (request, response) -> {
                    authorizationHeader.set(request.requestHeaders().get("Authorization"));
                    return response
                        .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                        .sendString(Mono.just("{\"balance\":5000.25}"));
                });
            })
            .bindNow();

        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        var properties = properties(server.port(), Duration.ofSeconds(1));
        var registration = ClientRegistration
            .withRegistrationId("payment-service")
            .clientId("market-app")
            .clientSecret("test-client-secret")
            .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
            .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
            .tokenUri("http://127.0.0.1:" + server.port() + "/oauth/token")
            .scope("payment.read", "payment.write")
            .build();
        var registrations = new InMemoryReactiveClientRegistrationRepository(registration);
        var authorizedClients = new InMemoryReactiveOAuth2AuthorizedClientService(registrations);
        var configuration = new PaymentClientConfiguration();
        var authorizedClientManager = configuration.paymentAuthorizedClientManager(
            registrations,
            authorizedClients,
            properties
        );
        var securedGateway = new GeneratedPaymentGateway(
            configuration.paymentsApi(
                properties,
                objectMapper,
                authorizedClientManager,
                authorizedClients
            ),
            properties,
            objectMapper
        );

        StepVerifier.create(securedGateway.getBalance(CUSTOMER_ID)
                .then(securedGateway.getBalance(CUSTOMER_ID)))
            .expectNext(new BigDecimal("5000.25"))
            .verifyComplete();

        assertEquals(1, tokenRequests.get());
        assertTrue(tokenAuthorizationHeader.get().startsWith("Basic "));
        assertTrue(tokenRequestBody.get().contains("grant_type=client_credentials"));
        assertEquals("Bearer payment-access-token", authorizationHeader.get());
    }

    @Test
    void shouldEvictRejectedTokenBeforeNextPaymentRequest() {
        UUID requestId = UUID.randomUUID();
        var tokenRequests = new AtomicInteger();
        var paymentRequests = new AtomicInteger();
        var authorizationHeaders = new CopyOnWriteArrayList<String>();
        server = HttpServer.create()
            .host("127.0.0.1")
            .port(0)
            .route(routes -> {
                routes.post("/oauth/token", (request, response) -> {
                    int tokenNumber = tokenRequests.incrementAndGet();
                    return request.receive()
                        .aggregate()
                        .then(response
                            .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                            .sendString(Mono.just("""
                                {
                                  "access_token":"payment-access-token-%d",
                                  "token_type":"Bearer",
                                  "expires_in":300,
                                  "scope":"payment.read payment.write"
                                }
                                """.formatted(tokenNumber)))
                            .then());
                });
                routes.post("/api/v1/payment", (request, response) -> {
                    authorizationHeaders.add(request.requestHeaders().get("Authorization"));
                    if (paymentRequests.incrementAndGet() == 1) {
                        return response
                            .status(401)
                            .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                            .sendString(Mono.just("""
                                {"code":"UNAUTHORIZED","message":"invalid token"}
                                """));
                    }
                    return response
                        .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                        .sendString(Mono.just("""
                            {
                              "requestId":"%s",
                              "amount":125.50,
                              "remainingBalance":874.50
                            }
                            """.formatted(requestId)));
                });
            })
            .bindNow();

        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        var properties = properties(server.port(), Duration.ofSeconds(1));
        var registration = ClientRegistration
            .withRegistrationId("payment-service")
            .clientId("market-app")
            .clientSecret("test-client-secret")
            .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
            .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
            .tokenUri("http://127.0.0.1:" + server.port() + "/oauth/token")
            .scope("payment.read", "payment.write")
            .build();
        var registrations = new InMemoryReactiveClientRegistrationRepository(registration);
        var authorizedClients = new InMemoryReactiveOAuth2AuthorizedClientService(registrations);
        var configuration = new PaymentClientConfiguration();
        var authorizedClientManager = configuration.paymentAuthorizedClientManager(
            registrations,
            authorizedClients,
            properties
        );
        var securedGateway = new GeneratedPaymentGateway(
            configuration.paymentsApi(
                properties,
                objectMapper,
                authorizedClientManager,
                authorizedClients
            ),
            properties,
            objectMapper
        );

        StepVerifier.create(securedGateway.pay(
                CUSTOMER_ID,
                requestId,
                new BigDecimal("125.50")
            ))
            .expectError(PaymentRejectedException.class)
            .verify();

        StepVerifier.create(securedGateway.pay(
                CUSTOMER_ID,
                requestId,
                new BigDecimal("125.50")
            ))
            .expectNext(new PaymentReceipt(
                requestId,
                new BigDecimal("125.50"),
                new BigDecimal("874.50")
            ))
            .verifyComplete();

        assertEquals(2, tokenRequests.get());
        assertEquals(
            java.util.List.of(
                "Bearer payment-access-token-1",
                "Bearer payment-access-token-2"
            ),
            authorizationHeaders
        );
    }

    @Test
    void shouldTreatInvalidClientFromTokenEndpointAsDefinitiveRejection() {
        var tokenRequests = new AtomicInteger();
        server = HttpServer.create()
            .host("127.0.0.1")
            .port(0)
            .route(routes -> routes.post("/oauth/token", (request, response) -> {
                tokenRequests.incrementAndGet();
                return request.receive()
                    .aggregate()
                    .then(response
                        .status(401)
                        .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                        .sendString(Mono.just("""
                            {
                              "error":"invalid_client",
                              "error_description":"bad client credentials"
                            }
                            """))
                        .then());
            }))
            .bindNow();

        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        var properties = properties(server.port(), Duration.ofSeconds(1));
        var registration = ClientRegistration
            .withRegistrationId("payment-service")
            .clientId("market-app")
            .clientSecret("wrong-client-secret")
            .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
            .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
            .tokenUri("http://127.0.0.1:" + server.port() + "/oauth/token")
            .scope("payment.read", "payment.write")
            .build();
        var registrations = new InMemoryReactiveClientRegistrationRepository(registration);
        var authorizedClients = new InMemoryReactiveOAuth2AuthorizedClientService(registrations);
        var configuration = new PaymentClientConfiguration();
        var authorizedClientManager = configuration.paymentAuthorizedClientManager(
            registrations,
            authorizedClients,
            properties
        );
        var securedGateway = new GeneratedPaymentGateway(
            configuration.paymentsApi(
                properties,
                objectMapper,
                authorizedClientManager,
                authorizedClients
            ),
            properties,
            objectMapper
        );

        StepVerifier.create(securedGateway.pay(
                CUSTOMER_ID,
                UUID.randomUUID(),
                BigDecimal.ONE
            ))
            .expectError(PaymentRejectedException.class)
            .verify();

        assertEquals(1, tokenRequests.get());
    }

    @Test
    void shouldSendGeneratedPaymentJsonAndReadResponse() {
        UUID requestId = UUID.randomUUID();
        var receivedBody = new AtomicReference<String>();
        var receivedCustomerId = new AtomicReference<String>();
        server = HttpServer.create()
            .host("127.0.0.1")
            .port(0)
            .route(routes -> routes.post("/api/v1/payment", (request, response) -> request
                .receive()
                .aggregate()
                .asString()
                .flatMap(body -> {
                    receivedBody.set(body);
                    receivedCustomerId.set(request.requestHeaders().get("X-Customer-Id"));
                    return response
                        .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                        .sendString(Mono.just("""
                            {"requestId":"%s","amount":125.50,"remainingBalance":874.50}
                            """.formatted(requestId)))
                        .then();
                })))
            .bindNow();

        StepVerifier.create(gateway().pay(CUSTOMER_ID, requestId, new BigDecimal("125.50")))
            .assertNext(receipt -> {
                assertEquals(requestId, receipt.requestId());
                assertEquals(0, new BigDecimal("125.50").compareTo(receipt.amount()));
                assertEquals(0, new BigDecimal("874.50").compareTo(receipt.remainingBalance()));
            })
            .verifyComplete();

        assertTrue(receivedBody.get().contains("\"requestId\":\"" + requestId + "\""));
        assertTrue(receivedBody.get().contains("\"amount\":125.50"));
        assertEquals(CUSTOMER_ID.toString(), receivedCustomerId.get());
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

        StepVerifier.create(gateway().pay(
                CUSTOMER_ID,
                UUID.randomUUID(),
                new BigDecimal("20.00")
            ))
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

        StepVerifier.create(gateway(stoppedPort).getBalance(CUSTOMER_ID))
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

        StepVerifier.create(gateway(Duration.ofMillis(100)).getBalance(CUSTOMER_ID))
            .expectError(PaymentServiceUnavailableException.class)
            .verify();

        StepVerifier.create(gateway(Duration.ofMillis(100))
                .pay(CUSTOMER_ID, UUID.randomUUID(), BigDecimal.ONE))
            .expectError(PaymentServiceUnavailableException.class)
            .verify();
    }

    @Test
    void shouldKeepIdempotencyConflictAvailableForReconciliation() {
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

        StepVerifier.create(gateway().pay(CUSTOMER_ID, UUID.randomUUID(), BigDecimal.ONE))
            .expectErrorMatches(error -> error instanceof PaymentServiceUnavailableException
                && error.getMessage().contains("идемпотентности"))
            .verify();
    }

    @Test
    void shouldMapPaymentAuthorizationFailureToRejectedPayment() {
        server = HttpServer.create()
            .host("127.0.0.1")
            .port(0)
            .route(routes -> routes.post("/api/v1/payment", (request, response) -> response
                .status(401)
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .sendString(Mono.just("""
                    {
                      "code":"UNAUTHORIZED",
                      "message":"invalid token"
                    }
                    """))))
            .bindNow();

        StepVerifier.create(gateway().pay(CUSTOMER_ID, UUID.randomUUID(), BigDecimal.ONE))
            .expectErrorMatches(error -> error instanceof PaymentRejectedException
                && error.getMessage().contains("авторизации"))
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

        StepVerifier.create(gateway().pay(CUSTOMER_ID, expectedRequestId, BigDecimal.ONE))
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
        var properties = properties(port, responseTimeout);
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

    private PaymentClientProperties properties(int port, Duration responseTimeout) {
        return new PaymentClientProperties(
            URI.create("http://127.0.0.1:" + port),
            Duration.ofMillis(300),
            responseTimeout,
            Duration.ofMillis(200)
        );
    }
}
