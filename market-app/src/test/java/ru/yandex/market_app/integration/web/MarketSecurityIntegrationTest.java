package ru.yandex.market_app.integration.web;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.yandex.market_app.integration.ReactiveIntegrationTest;
import ru.yandex.market_app.integration.ReactiveIntegrationTestSupport;
import ru.yandex.market_app.repository.UserAccountRepository;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.csrf;

@ReactiveIntegrationTest
class MarketSecurityIntegrationTest extends ReactiveIntegrationTestSupport {

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private UserAccountRepository userAccountRepository;

    @Test
    void shouldStoreOnlyBcryptPasswordHashesAndAuthenticateSeededUsers() {
        StepVerifier.create(userAccountRepository.findByUsername("alice"))
            .assertNext(account -> {
                assertFalse(account.passwordHash().contains("alice123"));
                assertTrue(account.passwordHash().matches("^\\$2[aby]\\$12\\$.+"));
            })
            .verifyComplete();

        login("alice", "alice123")
            .expectStatus().is3xxRedirection()
            .expectCookie().exists("SESSION");

        login("alice", "wrong-password")
            .expectStatus().is3xxRedirection()
            .expectHeader().valueEquals(HttpHeaders.LOCATION, "/login?error");
    }

    @Test
    void shouldProtectPrivateEndpointsAndHideAnonymousControls() {
        StepVerifier.create(resetDatabase()).verifyComplete();

        webTestClient.get()
            .uri("/items")
            .exchange()
            .expectStatus().isOk()
            .expectBody(String.class)
            .value(body -> {
                assertTrue(body.contains("href=\"/login\""));
                assertFalse(body.contains("href=\"/cart/items\""));
                assertFalse(body.contains("href=\"/orders\""));
                assertFalse(body.contains("action=\"/items\""));
                assertFalse(body.contains("action=\"/logout\""));
            });

        webTestClient.get()
            .uri("/items/1")
            .exchange()
            .expectStatus().isOk()
            .expectBody(String.class)
            .value(body -> {
                assertTrue(body.contains("href=\"/login\""));
                assertFalse(body.contains("href=\"/cart/items\""));
                assertFalse(body.contains("href=\"/orders\""));
                assertFalse(body.contains("action=\"/items/1\""));
                assertFalse(body.contains("action=\"/logout\""));
            });

        authenticatedAsAlice(webTestClient).get()
            .uri("/items")
            .exchange()
            .expectStatus().isOk()
            .expectBody(String.class)
            .value(body -> {
                assertTrue(body.contains("href=\"/cart/items\""));
                assertTrue(body.contains("href=\"/orders\""));
                assertTrue(body.contains("action=\"/items\""));
                assertTrue(body.contains("action=\"/logout\""));
                assertFalse(body.contains("href=\"/login\""));
            });
    }

    @ParameterizedTest
    @MethodSource("privateGetRoutes")
    void shouldRedirectAnonymousUserFromEveryPrivateGetRoute(String uri) {
        StepVerifier.create(resetDatabase()).verifyComplete();

        webTestClient.get()
            .uri(uri)
            .exchange()
            .expectStatus().is3xxRedirection()
            .expectHeader().value(HttpHeaders.LOCATION, location ->
                assertTrue(location.endsWith("/login")));
    }

    @ParameterizedTest
    @MethodSource("privatePostRequests")
    void shouldRejectEveryAnonymousMutationWithoutChangingDatabase(
        String uri,
        String formBody
    ) {
        StepVerifier.create(resetDatabase()).verifyComplete();

        webTestClient.mutateWith(csrf())
            .post()
            .uri(uri)
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .bodyValue(formBody)
            .exchange()
            .expectStatus().is3xxRedirection()
            .expectHeader().value(HttpHeaders.LOCATION, location ->
                assertTrue(location.endsWith("/login")));

        StepVerifier.create(privateStateRowCount())
            .assertNext(total -> assertEquals(0L, total))
            .verifyComplete();
    }

    @Test
    void shouldRejectStateChangesWithoutCsrf() {
        StepVerifier.create(resetDatabase()).verifyComplete();

        authenticatedAsAliceWithoutCsrf(webTestClient).post()
            .uri("/items")
            .body(BodyInserters.fromFormData("id", "1")
                .with("action", "PLUS"))
            .exchange()
            .expectStatus().isForbidden();
    }

    @Test
    void shouldInvalidateSessionAndExpireCookieOnLogout() {
        var loginResult = login("bob", "bob123")
            .expectStatus().is3xxRedirection()
            .returnResult(Void.class);
        ResponseCookie session = loginResult.getResponseCookies().getFirst("SESSION");
        assertNotNull(session);

        webTestClient.get()
            .uri("/orders")
            .cookie("SESSION", session.getValue())
            .exchange()
            .expectStatus().isOk();

        var logoutResult = webTestClient.mutateWith(csrf())
            .post()
            .uri("/logout")
            .cookie("SESSION", session.getValue())
            .exchange()
            .expectStatus().is3xxRedirection()
            .expectHeader().valueEquals(HttpHeaders.LOCATION, "/items")
            .returnResult(Void.class);

        assertTrue(logoutResult.getResponseCookies().get("SESSION").stream()
            .anyMatch(cookie -> cookie.getMaxAge().isZero()));

        webTestClient.get()
            .uri("/orders")
            .cookie("SESSION", session.getValue())
            .exchange()
            .expectStatus().is3xxRedirection()
            .expectHeader().value(HttpHeaders.LOCATION, location ->
                assertTrue(location.endsWith("/login")));
    }

    private WebTestClient.ResponseSpec login(String username, String password) {
        return webTestClient.mutateWith(csrf())
            .post()
            .uri("/login")
            .body(BodyInserters.fromFormData("username", username)
                .with("password", password))
            .exchange();
    }

    private Mono<Long> privateStateRowCount() {
        return databaseClient.sql("""
                SELECT (SELECT COUNT(*) FROM market.basket_product)
                     + (SELECT COUNT(*) FROM market."order") AS total
                """)
            .map((row, metadata) -> row.get("total", Long.class))
            .one();
    }

    private static Stream<String> privateGetRoutes() {
        return Stream.of(
            "/cart/items",
            "/orders",
            "/orders/1"
        );
    }

    private static Stream<Arguments> privatePostRequests() {
        return Stream.of(
            Arguments.of(
                "/items",
                "id=1&action=PLUS&search=&sort=NO&pageNumber=1&pageSize=5"
            ),
            Arguments.of("/items/1", "action=PLUS"),
            Arguments.of("/cart/items", "id=1&action=PLUS"),
            Arguments.of("/buy", "")
        );
    }
}
