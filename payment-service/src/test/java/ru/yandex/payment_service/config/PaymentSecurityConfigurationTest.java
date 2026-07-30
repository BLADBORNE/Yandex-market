package ru.yandex.payment_service.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.security.oauth2.resource.OAuth2ResourceServerProperties;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaymentSecurityConfigurationTest {

    private static final String ISSUER = "http://issuer.example/realms/market";

    private final PaymentSecurityConfiguration configuration = configuration();

    @Test
    void shouldAcceptExpectedIssuerAudienceAndAuthorizedParty() {
        assertFalse(configuration.paymentTokenValidator()
            .validate(jwt(ISSUER, List.of("payment-service"), "market-app"))
            .hasErrors());
    }

    @Test
    void shouldRejectWrongIssuer() {
        assertTrue(configuration.paymentTokenValidator()
            .validate(jwt("http://wrong.example", List.of("payment-service"), "market-app"))
            .hasErrors());
    }

    @Test
    void shouldRejectMissingPaymentAudience() {
        assertTrue(configuration.paymentTokenValidator()
            .validate(jwt(ISSUER, List.of("account"), "market-app"))
            .hasErrors());
    }

    @Test
    void shouldRejectAnotherAuthorizedParty() {
        assertTrue(configuration.paymentTokenValidator()
            .validate(jwt(ISSUER, List.of("payment-service"), "untrusted-app"))
            .hasErrors());
    }

    @Test
    void shouldRejectExpiredToken() {
        Instant now = Instant.now();
        Jwt expired = Jwt.withTokenValue("expired-token")
            .header("alg", "RS256")
            .issuer(ISSUER)
            .subject("service-account-market-app")
            .audience(List.of("payment-service"))
            .issuedAt(now.minusSeconds(300))
            .expiresAt(now.minusSeconds(120))
            .claim("azp", "market-app")
            .build();

        assertTrue(configuration.paymentTokenValidator()
            .validate(expired)
            .hasErrors());
    }

    private Jwt jwt(String issuer, List<String> audience, String authorizedParty) {
        Instant now = Instant.now();
        return Jwt.withTokenValue("test-token")
            .header("alg", "RS256")
            .issuer(issuer)
            .subject("service-account-market-app")
            .audience(audience)
            .issuedAt(now.minusSeconds(5))
            .expiresAt(now.plusSeconds(60))
            .claim("azp", authorizedParty)
            .build();
    }

    private PaymentSecurityConfiguration configuration() {
        var resourceServerProperties = new OAuth2ResourceServerProperties();
        resourceServerProperties.getJwt().setIssuerUri(ISSUER);
        resourceServerProperties.getJwt().setJwkSetUri(ISSUER + "/certs");
        resourceServerProperties.getJwt().setAudiences(List.of("payment-service"));
        return new PaymentSecurityConfiguration(
            new PaymentJwtProperties("market-app"),
            resourceServerProperties,
            null
        );
    }
}
