package ru.yandex.market_app.integration;

import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.r2dbc.connection.init.ResourceDatabasePopulator;
import org.springframework.r2dbc.core.DatabaseClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
class SchemaMigrationIntegrationTest {

    private static final String SPRINT_SEVEN_SCHEMA = """
        CREATE SCHEMA market;

        CREATE TABLE market.product
        (
            id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
            title       VARCHAR(100)   NOT NULL UNIQUE,
            description VARCHAR(255)   NOT NULL,
            img_path    VARCHAR(100)   NOT NULL UNIQUE,
            price       NUMERIC(19, 2) NOT NULL CHECK (price > 0)
        );

        CREATE TABLE market.basket
        (
            id     BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
            status VARCHAR(20) NOT NULL CHECK (status IN ('ACTIVE', 'CLOSED'))
        );

        CREATE UNIQUE INDEX basket_single_active_idx
            ON market.basket (status)
            WHERE status = 'ACTIVE';

        CREATE TABLE market."order"
        (
            id        BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
            basket_id BIGINT        NOT NULL REFERENCES market.basket (id),
            sum       NUMERIC(19, 2) NOT NULL CHECK (sum >= 0)
        );

        CREATE TABLE market.basket_product
        (
            product_id BIGINT  NOT NULL REFERENCES market.product (id),
            basket_id  BIGINT  NOT NULL REFERENCES market.basket (id),
            count      INTEGER NOT NULL CHECK (count > 0),
            PRIMARY KEY (product_id, basket_id)
        );

        CREATE TABLE market.order_item
        (
            order_id   BIGINT         NOT NULL REFERENCES market."order" (id) ON DELETE CASCADE,
            product_id BIGINT         NOT NULL,
            title      VARCHAR(100)   NOT NULL,
            price      NUMERIC(19, 2) NOT NULL CHECK (price >= 0),
            count      INTEGER        NOT NULL CHECK (count > 0),
            PRIMARY KEY (order_id, product_id)
        );

        INSERT INTO market.product (title, description, img_path, price)
        VALUES ('Legacy product', 'Sprint 7 data', 'images/legacy.jpg', 125.50);

        INSERT INTO market.basket (status)
        VALUES ('CLOSED'), ('ACTIVE');

        INSERT INTO market.basket_product (product_id, basket_id, count)
        VALUES (1, 1, 2), (1, 2, 1);

        INSERT INTO market."order" (basket_id, sum)
        VALUES (1, 251.00);

        INSERT INTO market.order_item (order_id, product_id, title, price, count)
        VALUES (1, 1, 'Legacy snapshot', 125.50, 2);
        """;

    private static final String PENDING_CHECKOUT_AFTER_FIRST_UPGRADE = """
        UPDATE market.basket
        SET status = 'CHECKOUT'
        WHERE id = 2;

        INSERT INTO market."order" (
            basket_id,
            user_id,
            sum,
            status,
            payment_account_id,
            payment_request_id,
            payment_attempt_id,
            payment_attempt_started_at
        )
        SELECT 2,
               id,
               125.50,
               'PENDING',
               payment_account_id,
               '40000000-0000-0000-0000-000000000001',
               '40000000-0000-0000-0000-000000000002',
               CURRENT_TIMESTAMP - INTERVAL '1 minute'
        FROM market.user_account
        WHERE username = 'alice';

        INSERT INTO market.order_item (order_id, product_id, title, price, count)
        VALUES (2, 1, 'Pending snapshot', 125.50, 1);
        """;

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>("postgres:17-alpine");

    @Test
    void shouldUpgradeSprintSevenDataAndRemainIdempotent() {
        ConnectionFactory connectionFactory = ConnectionFactories.get(
            "r2dbc:postgresql://%s:%s@%s:%d/%s".formatted(
                POSTGRES.getUsername(),
                POSTGRES.getPassword(),
                POSTGRES.getHost(),
                POSTGRES.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT),
                POSTGRES.getDatabaseName()
            )
        );
        DatabaseClient databaseClient = DatabaseClient.create(connectionFactory);

        StepVerifier.create(applySchema(connectionFactory)
                .then(databaseClient.sql("""
                        SELECT u.username,
                               b.user_id = o.user_id AS owner_matches,
                               oi.title,
                               oi.count,
                               o.status,
                               o.payment_request_id IS NULL AS legacy_payment_id_is_null
                        FROM market."order" o
                        JOIN market.basket b ON b.id = o.basket_id
                        JOIN market.user_account u ON u.id = o.user_id
                        JOIN market.order_item oi ON oi.order_id = o.id
                        WHERE o.id = 1
                        """)
                    .map((row, metadata) -> new MigrationResult(
                        row.get("username", String.class),
                        Boolean.TRUE.equals(row.get("owner_matches", Boolean.class)),
                        row.get("title", String.class),
                        row.get("count", Integer.class),
                        row.get("status", String.class),
                        Boolean.TRUE.equals(row.get("legacy_payment_id_is_null", Boolean.class))
                    ))
                    .one()))
            .assertNext(result -> {
                assertEquals("alice", result.username());
                assertTrue(result.ownerMatches());
                assertEquals("Legacy snapshot", result.itemTitle());
                assertEquals(2, result.itemCount());
                assertEquals("COMPLETED", result.orderStatus());
                assertTrue(result.legacyPaymentIdIsNull());
            })
            .verifyComplete();

        StepVerifier.create(databaseClient.sql("""
                    SELECT o.status AS order_status,
                           b.status AS basket_status,
                           o.payment_request_id,
                           o.payment_attempt_id,
                           o.payment_attempt_started_at IS NOT NULL
                               AS payment_attempt_started,
                           o.payment_attempted
                    FROM market."order" o
                    JOIN market.basket b ON b.id = o.basket_id
                    WHERE o.id = 2
                    """)
                .map((row, metadata) -> new PendingMigrationResult(
                    row.get("order_status", String.class),
                    row.get("basket_status", String.class),
                    row.get("payment_request_id", java.util.UUID.class),
                    row.get("payment_attempt_id", java.util.UUID.class),
                    Boolean.TRUE.equals(row.get("payment_attempt_started", Boolean.class)),
                    Boolean.TRUE.equals(row.get("payment_attempted", Boolean.class))
                ))
                .one())
            .assertNext(result -> {
                assertEquals("PENDING", result.orderStatus());
                assertEquals("CHECKOUT", result.basketStatus());
                assertEquals(
                    java.util.UUID.fromString("40000000-0000-0000-0000-000000000001"),
                    result.paymentRequestId()
                );
                assertEquals(
                    java.util.UUID.fromString("40000000-0000-0000-0000-000000000002"),
                    result.paymentAttemptId()
                );
                assertTrue(result.paymentAttemptStarted());
                assertTrue(result.paymentAttempted());
            })
            .verifyComplete();

        StepVerifier.create(databaseClient.sql("""
                    INSERT INTO market.basket (user_id, status)
                    SELECT id, 'ACTIVE'
                    FROM market.user_account
                    WHERE username = 'bob'
                    """)
                .fetch()
                .rowsUpdated())
            .expectNext(1L)
            .verifyComplete();
    }

    private Mono<Void> applySchema(ConnectionFactory connectionFactory) {
        var sprintSeven = new ResourceDatabasePopulator(new ByteArrayResource(
            SPRINT_SEVEN_SCHEMA.getBytes(StandardCharsets.UTF_8)
        ));
        var sprintEight = new ResourceDatabasePopulator(
            new ClassPathResource("db/schema.sql")
        );
        var pendingCheckout = new ResourceDatabasePopulator(new ByteArrayResource(
            PENDING_CHECKOUT_AFTER_FIRST_UPGRADE.getBytes(StandardCharsets.UTF_8)
        ));

        return Mono.usingWhen(
            Mono.from(connectionFactory.create()),
            connection -> sprintSeven.populate(connection)
                .then(sprintEight.populate(connection))
                .then(pendingCheckout.populate(connection))
                .then(sprintEight.populate(connection)),
            connection -> Mono.from(connection.close())
        );
    }

    private record MigrationResult(
        String username,
        boolean ownerMatches,
        String itemTitle,
        Integer itemCount,
        String orderStatus,
        boolean legacyPaymentIdIsNull
    ) {
    }

    private record PendingMigrationResult(
        String orderStatus,
        String basketStatus,
        java.util.UUID paymentRequestId,
        java.util.UUID paymentAttemptId,
        boolean paymentAttemptStarted,
        boolean paymentAttempted
    ) {
    }
}
