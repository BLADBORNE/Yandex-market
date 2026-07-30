package ru.yandex.payment_service.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentJwtPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(
            ConfigurationPropertiesAutoConfiguration.class,
            ValidationAutoConfiguration.class
        ))
        .withUserConfiguration(PropertiesConfiguration.class);

    @Test
    void shouldBindCompleteJwtConfiguration() {
        contextRunner
            .withPropertyValues(validProperties())
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBean(PaymentJwtProperties.class).authorizedClientId())
                    .isEqualTo("market-app");
            });
    }

    @Test
    void shouldRejectBlankAuthorizedClientId() {
        contextRunner
            .withPropertyValues(
                "payment.security.jwt.authorized-client-id= "
            )
            .run(context -> assertThat(context).hasFailed());
    }

    private String[] validProperties() {
        return new String[] {
            "payment.security.jwt.authorized-client-id=market-app"
        };
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(PaymentJwtProperties.class)
    static class PropertiesConfiguration {
    }
}
