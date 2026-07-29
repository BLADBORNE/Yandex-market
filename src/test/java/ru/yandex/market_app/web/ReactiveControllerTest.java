package ru.yandex.market_app.web;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import reactor.core.publisher.Mono;
import ru.yandex.market_app.dto.GetListOrderModelDto;
import ru.yandex.market_app.dto.GetOrderModelDto;
import ru.yandex.market_app.dto.GetProductCartModelDto;
import ru.yandex.market_app.dto.GetProductModelDto;
import ru.yandex.market_app.dto.ItemDto;
import ru.yandex.market_app.dto.PageableResult;
import ru.yandex.market_app.dto.ProductResultDto;
import ru.yandex.market_app.exception.ItemNotFoundException;
import ru.yandex.market_app.exception.NotRemoveItemException;
import ru.yandex.market_app.exception.OperationNotSupportedException;
import ru.yandex.market_app.service.BasketService;
import ru.yandex.market_app.service.OrderService;
import ru.yandex.market_app.service.ProductService;

import java.math.BigDecimal;
import java.util.List;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static ru.yandex.market_app.model.ProductAction.DELETE;
import static ru.yandex.market_app.model.ProductAction.MINUS;
import static ru.yandex.market_app.model.ProductAction.PLUS;
import static ru.yandex.market_app.util.ProductPageableUtil.ProductSort.NO;
import static ru.yandex.market_app.util.ProductPageableUtil.ProductSort.PRICE;

@WebFluxTest(controllers = {
    ProductController.class,
    BasketController.class,
    OrderController.class,
    GlobalExceptionHandler.class
})
class ReactiveControllerTest {

    @Autowired
    private WebTestClient webTestClient;

    @MockitoBean
    private ProductService productService;

    @MockitoBean
    private BasketService basketService;

    @MockitoBean
    private OrderService orderService;

    @Test
    void shouldRenderCatalogAndMapPagingParameters() {
        var products = List.of(List.of(product(1L, "Ноутбук", 2)));
        var paging = PageableResult.init(7, 2, true, true);
        when(productService.getProducts(eq("ноутбук"), eq(PRICE), any(Pageable.class)))
            .thenReturn(Mono.just(GetProductModelDto.builder()
                .items(products)
                .search("ноутбук")
                .sort(PRICE.name())
                .paging(paging)
                .build()));

        webTestClient.get()
            .uri("/items?search=ноутбук&sort=PRICE&pageNumber=2&pageSize=7")
            .exchange()
            .expectStatus().isOk()
            .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_HTML)
            .expectBody(String.class)
            .value(body -> {
                assertTrue(body.contains("Ноутбук"));
                assertTrue(body.contains("Страница: 2"));
                assertTrue(body.contains("value=\"PRICE\" selected"));
            });

        var pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(productService).getProducts(eq("ноутбук"), eq(PRICE), pageableCaptor.capture());
        Pageable pageable = pageableCaptor.getValue();
        assertEquals(1, pageable.getPageNumber());
        assertEquals(7, pageable.getPageSize());
        Sort.Order priceOrder = pageable.getSort().getOrderFor("price");
        assertNotNull(priceOrder);
        assertEquals(Sort.Direction.ASC, priceOrder.getDirection());
    }

    @Test
    void shouldSupportRootCatalogWithDefaults() {
        when(productService.getProducts(eq(""), eq(NO), any(Pageable.class)))
            .thenReturn(Mono.just(GetProductModelDto.builder()
                .items(List.of())
                .search("")
                .sort(NO.name())
                .paging(PageableResult.init(5, 1, false, false))
                .build()));

        webTestClient.get()
            .uri("/")
            .exchange()
            .expectStatus().isOk()
            .expectBody(String.class)
            .value(body -> assertTrue(body.contains("Страница: 1")));

        var pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(productService).getProducts(eq(""), eq(NO), pageableCaptor.capture());
        assertEquals(0, pageableCaptor.getValue().getPageNumber());
        assertEquals(5, pageableCaptor.getValue().getPageSize());
    }

    @Test
    void shouldMutateCatalogItemAndRedirectWithContext() {
        when(basketService.changeProductCountFromStartPage(15L, PLUS)).thenReturn(Mono.empty());

        webTestClient.post()
            .uri("/items?id=15&search=phone&sort=PRICE&pageNumber=3&pageSize=10&action=PLUS")
            .exchange()
            .expectStatus().is3xxRedirection()
            .expectHeader().valueEquals(
                "Location",
                "/items?search=phone&sort=PRICE&pageNumber=3&pageSize=10"
            );

        verify(basketService).changeProductCountFromStartPage(15L, PLUS);
    }

    @Test
    void shouldUseSafeDefaultsForCatalogMutationRedirect() {
        when(basketService.changeProductCountFromStartPage(15L, PLUS)).thenReturn(Mono.empty());

        webTestClient.post()
            .uri("/items?id=15&action=PLUS")
            .exchange()
            .expectStatus().is3xxRedirection()
            .expectHeader().valueEquals(
                "Location",
                "/items?search=&sort=NO&pageNumber=1&pageSize=5"
            );
    }

    @Test
    void shouldRenderProductAndUpdatedProduct() {
        when(productService.getItem(7L)).thenReturn(Mono.just(product(7L, "Телефон", 1)));
        when(basketService.changeProductCountFromItemPage(7L, MINUS))
            .thenReturn(Mono.just(product(7L, "Телефон", 0)));

        webTestClient.get()
            .uri("/items/7")
            .exchange()
            .expectStatus().isOk()
            .expectBody(String.class)
            .value(body -> {
                assertTrue(body.contains("Телефон"));
                assertTrue(body.contains("Описание"));
                assertTrue(body.contains("1000"));
            });

        webTestClient.post()
            .uri("/items/7")
            .body(BodyInserters.fromFormData("action", "MINUS"))
            .exchange()
            .expectStatus().isOk()
            .expectBody(String.class)
            .value(body -> assertTrue(body.contains(">0</span>")));
    }

    @Test
    void shouldRenderReactiveDomainErrors() {
        when(productService.getItem(99L))
            .thenReturn(Mono.error(new ItemNotFoundException("Товар не найден")));
        when(basketService.changeProductCountFromItemPage(7L, DELETE))
            .thenReturn(Mono.error(new OperationNotSupportedException("Операция не поддерживается")));

        webTestClient.get()
            .uri("/items/99")
            .exchange()
            .expectStatus().isNotFound()
            .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_HTML)
            .expectBody(String.class)
            .value(body -> {
                assertTrue(body.contains("404"));
                assertTrue(body.contains("Товар не найден"));
            });

        webTestClient.post()
            .uri("/items/7?action=DELETE")
            .exchange()
            .expectStatus().isBadRequest()
            .expectBody(String.class)
            .value(body -> assertTrue(body.contains("Операция не поддерживается")));
    }

    @Test
    void shouldRejectInvalidCatalogInput() {
        webTestClient.get().uri("/items?pageNumber=0")
            .exchange()
            .expectStatus().isBadRequest();

        webTestClient.get().uri("/items?pageSize=101")
            .exchange()
            .expectStatus().isBadRequest();

        webTestClient.post().uri("/items/7")
            .body(BodyInserters.fromFormData("action", "UNKNOWN"))
            .exchange()
            .expectStatus().isBadRequest();
    }

    @Test
    void shouldRejectMissingOrInvalidCatalogAndCartPostFields() {
        webTestClient.post()
            .uri("/items")
            .body(BodyInserters.fromFormData("id", "1"))
            .exchange()
            .expectStatus().isBadRequest();

        webTestClient.post()
            .uri("/items?id=1&action=UNKNOWN")
            .exchange()
            .expectStatus().isBadRequest();

        webTestClient.post()
            .uri("/cart/items")
            .body(BodyInserters.fromFormData("id", "1"))
            .exchange()
            .expectStatus().isBadRequest();

        webTestClient.post()
            .uri("/cart/items?id=1&action=UNKNOWN")
            .exchange()
            .expectStatus().isBadRequest();
    }

    @Test
    void shouldRenderCartForGetAndForPostMutation() {
        var before = cart(product(3L, "Монитор", 1), BigDecimal.valueOf(1000));
        var after = cart(product(3L, "Монитор", 2), BigDecimal.valueOf(2000));
        when(basketService.getCart())
            .thenReturn(Mono.just(before))
            .thenReturn(Mono.just(after));
        when(basketService.changeProductCountFromCartPage(3L, PLUS)).thenReturn(Mono.empty());

        webTestClient.get()
            .uri("/cart/items")
            .exchange()
            .expectStatus().isOk()
            .expectBody(String.class)
            .value(body -> {
                assertTrue(body.contains("Монитор"));
                assertTrue(body.contains("Итого: 1000"));
                assertTrue(body.contains("action=\"/buy\""));
            });

        webTestClient.post()
            .uri("/cart/items")
            .body(BodyInserters.fromFormData("id", "3").with("action", "PLUS"))
            .exchange()
            .expectStatus().isOk()
            .expectBody(String.class)
            .value(body -> {
                assertTrue(body.contains("Итого: 2000"));
                assertTrue(body.contains(">2</span>"));
            });

        verify(basketService).changeProductCountFromCartPage(3L, PLUS);
    }

    @Test
    void shouldRenderEmptyCartWithoutBuyButton() {
        when(basketService.getCart()).thenReturn(Mono.just(GetProductCartModelDto.builder()
            .items(List.of())
            .total(BigDecimal.ZERO)
            .build()));

        webTestClient.get()
            .uri("/cart/items")
            .exchange()
            .expectStatus().isOk()
            .expectBody(String.class)
            .value(body -> {
                assertTrue(!body.contains("action=\"/buy\""));
                assertTrue(!body.contains(">Купить<"));
            });
    }

    @Test
    void shouldRenderCartMutationError() {
        when(basketService.changeProductCountFromCartPage(3L, DELETE))
            .thenReturn(Mono.error(new NotRemoveItemException("Товара нет в корзине")));

        webTestClient.post()
            .uri("/cart/items?id=3&action=DELETE")
            .exchange()
            .expectStatus().isBadRequest()
            .expectBody(String.class)
            .value(body -> assertTrue(body.contains("Товара нет в корзине")));
    }

    @Test
    void shouldRenderOrdersAndOrderDetails() {
        var order = order(10L);
        when(orderService.getOrders()).thenReturn(Mono.just(
            GetListOrderModelDto.builder().orders(List.of(order)).build()
        ));
        when(orderService.getOrder(10L)).thenReturn(Mono.just(order));

        webTestClient.get()
            .uri("/orders")
            .exchange()
            .expectStatus().isOk()
            .expectBody(String.class)
            .value(body -> {
                assertTrue(body.contains("Заказ №10"));
                assertTrue(body.contains("/orders/10"));
                assertTrue(body.contains("Ноутбук"));
            });

        webTestClient.get()
            .uri("/orders/10?newOrder=true")
            .exchange()
            .expectStatus().isOk()
            .expectBody(String.class)
            .value(body -> {
                assertTrue(body.contains("Успешная покупка"));
                assertTrue(body.contains("Заказ №10"));
                assertTrue(body.contains("Сумма: 55000"));
            });
    }

    @Test
    void shouldDefaultNewOrderToFalseAndHandleMissingOrder() {
        when(orderService.getOrder(11L)).thenReturn(Mono.just(order(11L)));
        when(orderService.getOrder(99L))
            .thenReturn(Mono.error(new NoSuchElementException("Заказ не найден")));

        webTestClient.get()
            .uri("/orders/11")
            .exchange()
            .expectStatus().isOk()
            .expectBody(String.class)
            .value(body -> assertTrue(!body.contains("Успешная покупка")));

        webTestClient.get()
            .uri("/orders/99")
            .exchange()
            .expectStatus().isNotFound()
            .expectBody(String.class)
            .value(body -> assertTrue(body.contains("Заказ не найден")));
    }

    @Test
    void shouldBuyAndRedirectToCreatedOrder() {
        when(orderService.completeOrder()).thenReturn(Mono.just(42L));

        webTestClient.post()
            .uri("/buy")
            .exchange()
            .expectStatus().is3xxRedirection()
            .expectHeader().valueEquals("Location", "/orders/42?newOrder=true");

        verify(orderService).completeOrder();
    }

    private ProductResultDto product(Long id, String title, Integer count) {
        return ProductResultDto.builder()
            .id(id)
            .title(title)
            .description("Описание")
            .imgPath("images/product1.jpg")
            .price(BigDecimal.valueOf(1000))
            .count(count)
            .build();
    }

    private GetProductCartModelDto cart(ProductResultDto item, BigDecimal total) {
        return GetProductCartModelDto.builder()
            .items(List.of(item))
            .total(total)
            .build();
    }

    private GetOrderModelDto order(Long id) {
        return GetOrderModelDto.builder()
            .id(id)
            .items(List.of(ItemDto.builder()
                .id(1L)
                .title("Ноутбук")
                .price(BigDecimal.valueOf(55000))
                .count(1)
                .build()))
            .totalSum(BigDecimal.valueOf(55000))
            .build();
    }
}
