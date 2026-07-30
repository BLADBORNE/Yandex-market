package ru.yandex.market_app.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.ChannelOption;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import ru.yandex.market_app.payment.generated.api.PaymentsApi;
import ru.yandex.market_app.payment.generated.invoker.ApiClient;

@Configuration(proxyBeanMethods = false)
public class PaymentClientConfiguration {

    @Bean
    PaymentsApi paymentsApi(PaymentClientProperties properties, ObjectMapper objectMapper) {
        int connectTimeoutMillis = Math.toIntExact(properties.connectTimeout().toMillis());
        HttpClient httpClient = HttpClient.create()
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeoutMillis)
            .responseTimeout(properties.responseTimeout());

        WebClient webClient = ApiClient.buildWebClientBuilder(objectMapper)
            .clientConnector(new ReactorClientHttpConnector(httpClient))
            .build();
        ApiClient apiClient = new ApiClient(
            webClient,
            objectMapper,
            ApiClient.createDefaultDateFormat()
        ).setBasePath(properties.baseUrl().toString());

        return new PaymentsApi(apiClient);
    }
}
