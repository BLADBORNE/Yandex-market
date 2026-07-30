package ru.yandex.market_app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class MarketAppApplication {

    public static void main(String[] args) {
        SpringApplication.run(MarketAppApplication.class, args);
    }

}
