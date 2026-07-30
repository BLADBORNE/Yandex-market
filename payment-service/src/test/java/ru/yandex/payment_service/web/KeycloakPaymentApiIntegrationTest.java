package ru.yandex.payment_service.web;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.yandex.payment_service.generated.model.BalanceResponse;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertNotNull;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class KeycloakPaymentApiIntegrationTest {

    private static final String CLIENT_SECRET = "integration-test-secret";
    private static final UUID CUSTOMER_ID = UUID.fromString(
        "30000000-0000-0000-0000-000000000001"
    );

    @Container
    private static final GenericContainer<?> KEYCLOAK =
        new GenericContainer<>(DockerImageName.parse("quay.io/keycloak/keycloak:26.7.0"))
            .withExposedPorts(8080)
            .withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", "admin")
            .withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", "integration-admin-secret")
            .withEnv("PAYMENT_OAUTH_CLIENT_SECRET", CLIENT_SECRET)
            .withCopyFileToContainer(
                MountableFile.forClasspathResource("market-realm.json"),
                "/opt/keycloak/data/import/market-realm.json"
            )
            .withCommand("start-dev", "--import-realm")
            .waitingFor(Wait.forHttp("/realms/market/.well-known/openid-configuration")
                .forPort(8080)
                .forStatusCode(200)
                .withStartupTimeout(Duration.ofMinutes(2)));

    @Autowired
    private WebTestClient webTestClient;

    @DynamicPropertySource
    static void configureJwt(DynamicPropertyRegistry registry) {
        registry.add(
            "spring.security.oauth2.resourceserver.jwt.issuer-uri",
            KeycloakPaymentApiIntegrationTest::issuer
        );
        registry.add(
            "spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
            KeycloakPaymentApiIntegrationTest::jwkSet
        );
    }

    @Test
    void shouldAcceptRealClientCredentialsTokenIssuedByKeycloak() {
        var tokenHolder = new AtomicReference<String>();
        StepVerifier.create(accessToken(CLIENT_SECRET))
            .assertNext(tokenHolder::set)
            .verifyComplete();

        authorizedBalance(tokenHolder.get())
            .expectBody(BalanceResponse.class)
            .value(response -> assertNotNull(response.getBalance()));
    }

    @Test
    void shouldRejectInvalidClientSecretAtTokenEndpoint() {
        StepVerifier.create(accessToken("wrong-secret"))
            .expectError()
            .verify();
    }

    private WebTestClient.ResponseSpec authorizedBalance(String token) {
        return webTestClient.get()
            .uri("/api/v1/balance")
            .headers(headers -> headers.setBearerAuth(token))
            .header("X-Customer-Id", CUSTOMER_ID.toString())
            .accept(MediaType.APPLICATION_JSON)
            .exchange()
            .expectStatus().isOk();
    }

    private static Mono<String> accessToken(String clientSecret) {
        var form = new LinkedMultiValueMap<String, String>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", "market-app");
        form.add("client_secret", clientSecret);

        return WebClient.create()
            .post()
            .uri(tokenEndpoint())
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(BodyInserters.fromFormData(form))
            .retrieve()
            .bodyToMono(JsonNode.class)
            .map(response -> response.path("access_token").asText())
            .filter(token -> !token.isBlank())
            .switchIfEmpty(Mono.error(new IllegalStateException(
                "Keycloak не вернул access_token"
            )));
    }

    private static String issuer() {
        return "http://%s:%d/realms/market".formatted(
            KEYCLOAK.getHost(),
            KEYCLOAK.getMappedPort(8080)
        );
    }

    private static String jwkSet() {
        return issuer() + "/protocol/openid-connect/certs";
    }

    private static String tokenEndpoint() {
        return issuer() + "/protocol/openid-connect/token";
    }
}
