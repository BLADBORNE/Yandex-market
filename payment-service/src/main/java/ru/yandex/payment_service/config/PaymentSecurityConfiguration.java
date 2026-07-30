package ru.yandex.payment_service.config;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.security.oauth2.resource.OAuth2ResourceServerProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.security.web.server.savedrequest.NoOpServerRequestCache;
import ru.yandex.payment_service.web.PaymentSecurityErrorWriter;

import java.util.List;

@Configuration(proxyBeanMethods = false)
@EnableWebFluxSecurity
@RequiredArgsConstructor
public class PaymentSecurityConfiguration {

    private static final String BALANCE_ENDPOINT = "/api/v1/balance";
    private static final String PAYMENT_ENDPOINT = "/api/v1/payment";

    private final PaymentJwtProperties properties;
    private final OAuth2ResourceServerProperties resourceServerProperties;
    private final PaymentSecurityErrorWriter securityErrorWriter;

    @Bean
    SecurityWebFilterChain paymentSecurityWebFilterChain(
        ServerHttpSecurity http,
        ReactiveJwtDecoder paymentJwtDecoder
    ) {
        return http
            .csrf(ServerHttpSecurity.CsrfSpec::disable)
            .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
            .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
            .logout(ServerHttpSecurity.LogoutSpec::disable)
            .requestCache(cache -> cache.requestCache(NoOpServerRequestCache.getInstance()))
            .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
            .exceptionHandling(exceptions -> exceptions
                .authenticationEntryPoint(securityErrorWriter)
                .accessDeniedHandler(securityErrorWriter))
            .authorizeExchange(exchanges -> exchanges
                .pathMatchers("/actuator/health", "/actuator/health/**").permitAll()
                .pathMatchers(HttpMethod.GET, BALANCE_ENDPOINT).hasAuthority("SCOPE_payment.read")
                .pathMatchers(HttpMethod.POST, PAYMENT_ENDPOINT).hasAuthority("SCOPE_payment.write")
                .anyExchange().denyAll())
            .oauth2ResourceServer(resourceServer -> resourceServer
                .jwt(jwt -> jwt.jwtDecoder(paymentJwtDecoder))
                .authenticationEntryPoint(securityErrorWriter)
                .accessDeniedHandler(securityErrorWriter))
            .build();
    }

    @Bean
    ReactiveJwtDecoder paymentJwtDecoder() {
        var jwtProperties = resourceServerProperties.getJwt();
        var decoder = NimbusReactiveJwtDecoder
            .withJwkSetUri(jwtProperties.getJwkSetUri())
            .build();

        decoder.setJwtValidator(paymentTokenValidator());
        return decoder;
    }

    OAuth2TokenValidator<Jwt> paymentTokenValidator() {
        var jwtProperties = resourceServerProperties.getJwt();
        OAuth2TokenValidator<Jwt> issuerValidator = JwtValidators.createDefaultWithIssuer(
            jwtProperties.getIssuerUri()
        );
        OAuth2TokenValidator<Jwt> audienceValidator = new JwtClaimValidator<List<String>>(
            "aud",
            audiences -> audiences != null
                && jwtProperties.getAudiences().stream().anyMatch(audiences::contains)
        );
        OAuth2TokenValidator<Jwt> authorizedPartyValidator = new JwtClaimValidator<>(
            "azp",
            properties.authorizedClientId()::equals
        );

        return new DelegatingOAuth2TokenValidator<>(
            issuerValidator,
            audienceValidator,
            authorizedPartyValidator
        );
    }
}
