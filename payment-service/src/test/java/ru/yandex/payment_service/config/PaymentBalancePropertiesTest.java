package ru.yandex.payment_service.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentBalancePropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(
            ConfigurationPropertiesAutoConfiguration.class,
            ValidationAutoConfiguration.class
        ))
        .withUserConfiguration(PropertiesConfiguration.class);

    @Test
    void shouldBindValidInitialBalance() {
        contextRunner
            .withPropertyValues("payment.balance.initial=1234.56")
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBean(PaymentBalanceProperties.class).initial())
                    .isEqualByComparingTo(new BigDecimal("1234.56"));
            });
    }

    @Test
    void shouldRejectNegativeInitialBalance() {
        contextRunner
            .withPropertyValues("payment.balance.initial=-0.01")
            .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void shouldRejectInitialBalanceWithSubCentPrecision() {
        contextRunner
            .withPropertyValues("payment.balance.initial=10.001")
            .run(context -> assertThat(context).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(PaymentBalanceProperties.class)
    static class PropertiesConfiguration {
    }
}
