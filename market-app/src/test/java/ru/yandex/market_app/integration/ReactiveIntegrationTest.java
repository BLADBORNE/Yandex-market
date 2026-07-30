package ru.yandex.market_app.integration;

import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "spring.security.oauth2.client.registration.payment-service.client-id=test-client",
        "spring.security.oauth2.client.registration.payment-service.client-secret=test-secret",
        "spring.security.oauth2.client.registration.payment-service.provider=keycloak",
        "spring.security.oauth2.client.registration.payment-service.authorization-grant-type=client_credentials",
        "spring.security.oauth2.client.registration.payment-service.scope=payment.read,payment.write",
        "spring.security.oauth2.client.provider.keycloak.token-uri=http://127.0.0.1:1/oauth/token",
        "market.payment-service.retry-delay=1ms"
    }
)
@AutoConfigureWebTestClient
@Import(PostgreTestContainer.class)
public @interface ReactiveIntegrationTest {
}
