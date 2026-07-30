package ru.yandex.market_app.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.ChannelOption;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.client.AuthorizedClientServiceReactiveOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.client.endpoint.WebClientReactiveClientCredentialsTokenResponseClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import ru.yandex.market_app.payment.generated.api.PaymentsApi;
import ru.yandex.market_app.payment.generated.invoker.ApiClient;

@Configuration(proxyBeanMethods = false)
public class PaymentClientConfiguration {

    private static final String PAYMENT_CLIENT_REGISTRATION_ID = "payment-service";
    private static final Authentication APPLICATION_PRINCIPAL =
        UsernamePasswordAuthenticationToken.authenticated(
            "market-app",
            null,
            AuthorityUtils.createAuthorityList("ROLE_SYSTEM")
        );

    @Bean
    public ReactiveOAuth2AuthorizedClientManager paymentAuthorizedClientManager(
        ReactiveClientRegistrationRepository clientRegistrations,
        ReactiveOAuth2AuthorizedClientService authorizedClientService,
        PaymentClientProperties properties
    ) {
        var tokenResponseClient = new WebClientReactiveClientCredentialsTokenResponseClient();
        tokenResponseClient.setWebClient(WebClient.builder()
            .clientConnector(new ReactorClientHttpConnector(httpClient(properties)))
            .build());
        var authorizedClientProvider = ReactiveOAuth2AuthorizedClientProviderBuilder.builder()
            .clientCredentials(clientCredentials -> clientCredentials
                .accessTokenResponseClient(tokenResponseClient))
            .build();
        var authorizedClientManager = new AuthorizedClientServiceReactiveOAuth2AuthorizedClientManager(
            clientRegistrations,
            authorizedClientService
        );
        authorizedClientManager.setAuthorizedClientProvider(authorizedClientProvider);
        return authorizedClientManager;
    }

    @Bean
    public PaymentsApi paymentsApi(
        PaymentClientProperties properties,
        ObjectMapper objectMapper,
        ReactiveOAuth2AuthorizedClientManager authorizedClientManager,
        ReactiveOAuth2AuthorizedClientService authorizedClientService
    ) {
        WebClient webClient = ApiClient.buildWebClientBuilder(objectMapper)
            .clientConnector(new ReactorClientHttpConnector(httpClient(properties)))
            .filter(clientCredentials(authorizedClientManager, authorizedClientService))
            .build();
        ApiClient apiClient = new ApiClient(
            webClient,
            objectMapper,
            ApiClient.createDefaultDateFormat()
        ).setBasePath(properties.baseUrl().toString());

        return new PaymentsApi(apiClient);
    }

    private ExchangeFilterFunction clientCredentials(
        ReactiveOAuth2AuthorizedClientManager authorizedClientManager,
        ReactiveOAuth2AuthorizedClientService authorizedClientService
    ) {
        return (request, next) -> authorizedClientManager.authorize(
                OAuth2AuthorizeRequest
                    .withClientRegistrationId(PAYMENT_CLIENT_REGISTRATION_ID)
                    .principal(APPLICATION_PRINCIPAL)
                    .build()
            )
            .switchIfEmpty(Mono.error(new IllegalStateException(
                "Не удалось получить OAuth2-токен для payment-service"
            )))
            .flatMap(authorizedClient -> next.exchange(withBearerToken(request, authorizedClient
                    .getAccessToken()
                    .getTokenValue()))
                .flatMap(response -> evictRejectedToken(response, authorizedClientService)));
    }

    private ClientRequest withBearerToken(ClientRequest request, String tokenValue) {
        return ClientRequest.from(request)
            .headers(headers -> headers.setBearerAuth(tokenValue))
            .build();
    }

    private Mono<ClientResponse> evictRejectedToken(
        ClientResponse response,
        ReactiveOAuth2AuthorizedClientService authorizedClientService
    ) {
        if (response.statusCode() != HttpStatus.UNAUTHORIZED
            && response.statusCode() != HttpStatus.FORBIDDEN) {
            return Mono.just(response);
        }

        return authorizedClientService.removeAuthorizedClient(
                PAYMENT_CLIENT_REGISTRATION_ID,
                APPLICATION_PRINCIPAL.getName()
            )
            .thenReturn(response);
    }

    private HttpClient httpClient(PaymentClientProperties properties) {
        int connectTimeoutMillis = Math.toIntExact(properties.connectTimeout().toMillis());
        return HttpClient.create()
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeoutMillis)
            .responseTimeout(properties.responseTimeout());
    }
}
