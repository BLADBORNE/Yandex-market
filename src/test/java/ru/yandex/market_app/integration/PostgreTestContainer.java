package ru.yandex.market_app.integration;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;

public final class PostgreTestContainer {

    @Container
    @ServiceConnection
    private static final org.testcontainers.containers.PostgreSQLContainer<?> POSTGRES = new org.testcontainers.containers.PostgreSQLContainer<>("postgres:17");
}
