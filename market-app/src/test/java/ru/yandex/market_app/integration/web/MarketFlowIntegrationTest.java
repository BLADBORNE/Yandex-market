package ru.yandex.market_app.integration.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.embedded.netty.NettyWebServer;
import org.springframework.boot.web.reactive.context.ReactiveWebServerApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import reactor.test.StepVerifier;
import ru.yandex.market_app.integration.ReactiveIntegrationTest;
import ru.yandex.market_app.integration.ReactiveIntegrationTestSupport;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ReactiveIntegrationTest
class MarketFlowIntegrationTest extends ReactiveIntegrationTestSupport {

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private ReactiveWebServerApplicationContext applicationContext;

    @LocalServerPort
    private int serverPort;

    @Test
    void shouldServePublicCatalogThroughRealNettyPort() {
        StepVerifier.create(resetDatabase()).verifyComplete();
        assertInstanceOf(NettyWebServer.class, applicationContext.getWebServer());

        WebTestClient.bindToServer()
            .baseUrl("http://127.0.0.1:" + serverPort)
            .build()
            .get()
            .uri("/items")
            .exchange()
            .expectStatus().isOk()
            .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_HTML)
            .expectBody(String.class)
            .value(body -> assertTrue(body.contains("Витрина магазина")));
    }

    @Test
    void shouldRejectBuyingAnEmptyCart() {
        StepVerifier.create(resetDatabase()).verifyComplete();

        authenticatedAsAlice(webTestClient).post()
            .uri("/buy")
            .exchange()
            .expectStatus().isNotFound()
            .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_HTML)
            .expectBody(String.class)
            .value(body -> assertTrue(body.contains("Активная корзина не найдена")));
    }

    @Test
    void shouldRunCompleteCatalogCartCheckoutAndOrderFlow() {
        StepVerifier.create(resetDatabase()).verifyComplete();
        assertInstanceOf(NettyWebServer.class, applicationContext.getWebServer());
        WebTestClient aliceClient = authenticatedAsAlice(webTestClient);

        aliceClient.get()
            .uri("/items?search=Ноутбук&sort=ALPHA&pageNumber=1&pageSize=5")
            .exchange()
            .expectStatus().isOk()
            .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_HTML)
            .expectBody(String.class)
            .value(body -> {
                assertTrue(body.contains("Ноутбук ASUS VivoBook"));
                assertTrue(body.contains("Страница: 1"));
            });

        aliceClient.get()
            .uri("/images/product1.jpg")
            .exchange()
            .expectStatus().isOk()
            .expectHeader().contentTypeCompatibleWith(MediaType.IMAGE_JPEG)
            .expectBody(byte[].class)
            .value(image -> assertTrue(image.length > 0));

        aliceClient.post()
            .uri("/items")
            .body(BodyInserters.fromFormData("id", "1")
                .with("search", "")
                .with("sort", "NO")
                .with("pageNumber", "1")
                .with("pageSize", "5")
                .with("action", "PLUS"))
            .exchange()
            .expectStatus().is3xxRedirection()
            .expectHeader().valueEquals(
                "Location",
                "/items?search=&sort=NO&pageNumber=1&pageSize=5"
            );

        aliceClient.post()
            .uri("/cart/items")
            .body(BodyInserters.fromFormData("id", "1").with("action", "PLUS"))
            .exchange()
            .expectStatus().isOk()
            .expectBody(String.class)
            .value(body -> {
                assertTrue(body.contains("Ноутбук ASUS VivoBook"));
                assertTrue(body.contains(">2</span>"));
                assertTrue(body.contains("Итого: 109998"));
            });

        var buyResult = aliceClient.post()
            .uri("/buy")
            .exchange()
            .expectStatus().is3xxRedirection()
            .expectHeader().valueMatches("Location", "/orders/\\d+\\?newOrder=true")
            .returnResult(Void.class);

        String orderLocation = buyResult.getResponseHeaders().getFirst("Location");
        assertFalse(orderLocation == null || orderLocation.isBlank());

        aliceClient.get()
            .uri(orderLocation)
            .exchange()
            .expectStatus().isOk()
            .expectBody(String.class)
            .value(body -> {
                assertTrue(body.contains("Успешная покупка"));
                assertTrue(body.contains("Ноутбук ASUS VivoBook"));
                assertTrue(body.contains("Сумма: 109998"));
            });

        aliceClient.get()
            .uri("/orders")
            .exchange()
            .expectStatus().isOk()
            .expectBody(String.class)
            .value(body -> {
                assertTrue(body.contains("Ноутбук ASUS VivoBook"));
                assertTrue(body.contains("109998"));
            });

        aliceClient.get()
            .uri("/cart/items")
            .exchange()
            .expectStatus().isOk()
            .expectBody(String.class)
            .value(body -> assertFalse(body.contains(">Купить<")));
    }
}
