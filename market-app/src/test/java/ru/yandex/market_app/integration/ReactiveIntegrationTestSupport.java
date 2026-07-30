package ru.yandex.market_app.integration;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import ru.yandex.market_app.cache.ProductCatalogCache;
import ru.yandex.market_app.model.UserAccount;
import ru.yandex.market_app.security.MarketUserPrincipal;

import java.util.UUID;

import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockAuthentication;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.csrf;

public abstract class ReactiveIntegrationTestSupport {

    protected static final long ALICE_ID = 1L;
    protected static final long BOB_ID = 2L;
    protected static final UUID ALICE_PAYMENT_ACCOUNT =
        UUID.fromString("85a65fde-0492-4dcf-b1f4-dbc8f901ae72");
    protected static final UUID BOB_PAYMENT_ACCOUNT =
        UUID.fromString("0c9f6374-c617-4f97-bd90-3de6f7fdfac5");

    @Autowired
    protected DatabaseClient databaseClient;

    @Autowired
    protected ProductCatalogCache productCatalogCache;

    @Autowired
    protected PostgreTestContainer.TestPaymentGateway testPaymentGateway;

    protected Mono<Void> resetDatabase() {
        return Mono.fromRunnable(testPaymentGateway::reset)
            .then(databaseClient.sql("""
                TRUNCATE TABLE market.order_item, market."order", market.basket_product, market.basket
                RESTART IDENTITY CASCADE
                """)
                .fetch()
                .rowsUpdated())
            .then(productCatalogCache.evict());
    }

    protected WebTestClient authenticatedAsAlice(WebTestClient webTestClient) {
        return authenticated(webTestClient, principal(
            ALICE_ID,
            "alice",
            ALICE_PAYMENT_ACCOUNT
        ), true);
    }

    protected WebTestClient authenticatedAsAliceWithoutCsrf(WebTestClient webTestClient) {
        return authenticated(webTestClient, principal(
            ALICE_ID,
            "alice",
            ALICE_PAYMENT_ACCOUNT
        ), false);
    }

    protected WebTestClient authenticatedAsBob(WebTestClient webTestClient) {
        return authenticated(webTestClient, principal(
            BOB_ID,
            "bob",
            BOB_PAYMENT_ACCOUNT
        ), true);
    }

    private WebTestClient authenticated(
        WebTestClient webTestClient,
        MarketUserPrincipal principal,
        boolean includeCsrf
    ) {
        var authentication = UsernamePasswordAuthenticationToken.authenticated(
            principal,
            principal.getPassword(),
            principal.getAuthorities()
        );
        WebTestClient authenticated = webTestClient.mutateWith(mockAuthentication(authentication));
        return includeCsrf ? authenticated.mutateWith(csrf()) : authenticated;
    }

    private MarketUserPrincipal principal(Long userId, String username, UUID paymentAccountId) {
        return new MarketUserPrincipal(new UserAccount(
            userId,
            username,
            "$2a$12$test",
            true,
            paymentAccountId
        ));
    }
}
