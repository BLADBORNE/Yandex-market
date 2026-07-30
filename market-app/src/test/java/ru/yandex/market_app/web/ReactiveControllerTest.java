package ru.yandex.market_app.web;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
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
import ru.yandex.market_app.configuration.MarketSecurityConfiguration;
import ru.yandex.market_app.exception.ItemNotFoundException;
import ru.yandex.market_app.exception.NotRemoveItemException;
import ru.yandex.market_app.exception.OperationNotSupportedException;
import ru.yandex.market_app.payment.InsufficientFundsException;
import ru.yandex.market_app.payment.PaymentGateway;
import ru.yandex.market_app.payment.PaymentRejectedException;
import ru.yandex.market_app.payment.PaymentServiceUnavailableException;
import ru.yandex.market_app.model.UserAccount;
import ru.yandex.market_app.security.DatabaseReactiveUserDetailsService;
import ru.yandex.market_app.security.MarketUserPrincipal;
import ru.yandex.market_app.security.SessionCookieLogoutHandler;
import ru.yandex.market_app.service.BasketService;
import ru.yandex.market_app.service.OrderService;
import ru.yandex.market_app.service.ProductService;

import java.math.BigDecimal;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.csrf;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockAuthentication;
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
@Import({MarketSecurityConfiguration.class, SessionCookieLogoutHandler.class})
class ReactiveControllerTest {

    private static final long USER_ID = 1L;
    private static final UUID PAYMENT_ACCOUNT_ID =
        UUID.fromString("85a65fde-0492-4dcf-b1f4-dbc8f901ae72");

    @Autowired
    private WebTestClient webTestClient;

    @MockitoBean
    private ProductService productService;

    @MockitoBean
    private BasketService basketService;

    @MockitoBean
    private OrderService orderService;

    @MockitoBean
    private PaymentGateway paymentGateway;

    @MockitoBean
    private DatabaseReactiveUserDetailsService userDetailsService;

    private WebTestClient authenticatedClient;

    @BeforeEach
    void setUpSecurityClient() {
        var principal = new MarketUserPrincipal(new UserAccount(
            USER_ID,
            "alice",
            "$2a$12$test",
            true,
            PAYMENT_ACCOUNT_ID
        ));
        var authentication = UsernamePasswordAuthenticationToken.authenticated(
            principal,
            principal.getPassword(),
            principal.getAuthorities()
        );
        authenticatedClient = webTestClient
            .mutateWith(mockAuthentication(authentication))
            .mutateWith(csrf());
    }

    @Test
    void shouldRenderCatalogAndMapPagingParameters() {
        var products = List.of(List.of(product(1L, "Ноутбук", 2)));
        var paging = PageableResult.init(7, 2, true, true);
        when(productService.getProducts(eq("ноутбук"), eq(PRICE), any(Pageable.class), isNull()))
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
        verify(productService).getProducts(eq("ноутбук"), eq(PRICE), pageableCaptor.capture(), isNull());
        Pageable pageable = pageableCaptor.getValue();
        assertEquals(1, pageable.getPageNumber());
        assertEquals(7, pageable.getPageSize());
        Sort.Order priceOrder = pageable.getSort().getOrderFor("price");
        assertNotNull(priceOrder);
        assertEquals(Sort.Direction.ASC, priceOrder.getDirection());
    }

    @Test
    void shouldSupportRootCatalogWithDefaults() {
        when(productService.getProducts(eq(""), eq(NO), any(Pageable.class), isNull()))
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
        verify(productService).getProducts(eq(""), eq(NO), pageableCaptor.capture(), isNull());
        assertEquals(0, pageableCaptor.getValue().getPageNumber());
        assertEquals(5, pageableCaptor.getValue().getPageSize());
    }

    @Test
    void shouldMutateCatalogItemAndRedirectWithContext() {
        when(basketService.changeProductCountFromStartPage(USER_ID, 15L, PLUS)).thenReturn(Mono.empty());

        authenticatedClient.post()
            .uri("/items?id=15&search=phone&sort=PRICE&pageNumber=3&pageSize=10&action=PLUS")
            .exchange()
            .expectStatus().is3xxRedirection()
            .expectHeader().valueEquals(
                "Location",
                "/items?search=phone&sort=PRICE&pageNumber=3&pageSize=10"
            );

        verify(basketService).changeProductCountFromStartPage(USER_ID, 15L, PLUS);
    }

    @Test
    void shouldUseSafeDefaultsForCatalogMutationRedirect() {
        when(basketService.changeProductCountFromStartPage(USER_ID, 15L, PLUS)).thenReturn(Mono.empty());

        authenticatedClient.post()
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
        when(productService.getItem(7L, null))
            .thenReturn(Mono.just(product(7L, "Телефон", 1)));
        when(productService.getItem(7L, USER_ID))
            .thenReturn(Mono.just(product(7L, "Телефон", 0)));
        when(basketService.changeProductCountFromItemPage(USER_ID, 7L, MINUS))
            .thenReturn(Mono.empty());

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

        authenticatedClient.post()
            .uri("/items/7")
            .body(BodyInserters.fromFormData("action", "MINUS"))
            .exchange()
            .expectStatus().isOk()
            .expectBody(String.class)
            .value(body -> assertTrue(body.contains(">0</span>")));
    }

    @Test
    void shouldRenderReactiveDomainErrors() {
        when(productService.getItem(99L, null))
            .thenReturn(Mono.error(new ItemNotFoundException("Товар не найден")));
        when(basketService.changeProductCountFromItemPage(USER_ID, 7L, DELETE))
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

        authenticatedClient.post()
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

        authenticatedClient.post().uri("/items/7")
            .body(BodyInserters.fromFormData("action", "UNKNOWN"))
            .exchange()
            .expectStatus().isBadRequest();
    }

    @Test
    void shouldRejectMissingOrInvalidCatalogAndCartPostFields() {
        authenticatedClient.post()
            .uri("/items")
            .body(BodyInserters.fromFormData("id", "1"))
            .exchange()
            .expectStatus().isBadRequest();

        authenticatedClient.post()
            .uri("/items?id=1&action=UNKNOWN")
            .exchange()
            .expectStatus().isBadRequest();

        authenticatedClient.post()
            .uri("/cart/items")
            .body(BodyInserters.fromFormData("id", "1"))
            .exchange()
            .expectStatus().isBadRequest();

        authenticatedClient.post()
            .uri("/cart/items?id=1&action=UNKNOWN")
            .exchange()
            .expectStatus().isBadRequest();
    }

    @Test
    void shouldRenderCartForGetAndForPostMutation() {
        var before = cart(product(3L, "Монитор", 1), BigDecimal.valueOf(1000));
        var after = cart(product(3L, "Монитор", 2), BigDecimal.valueOf(2000));
        when(basketService.getCart(USER_ID))
            .thenReturn(Mono.just(before))
            .thenReturn(Mono.just(after));
        when(paymentGateway.getBalance(PAYMENT_ACCOUNT_ID))
            .thenReturn(Mono.just(BigDecimal.valueOf(100_000)));
        when(basketService.changeProductCountFromCartPage(USER_ID, 3L, PLUS)).thenReturn(Mono.empty());

        authenticatedClient.get()
            .uri("/cart/items")
            .exchange()
            .expectStatus().isOk()
            .expectBody(String.class)
            .value(body -> {
                assertTrue(body.contains("Монитор"));
                assertTrue(body.contains("Итого: 1000"));
                assertTrue(body.contains("action=\"/buy\""));
            });

        authenticatedClient.post()
            .uri("/cart/items")
            .body(BodyInserters.fromFormData("id", "3").with("action", "PLUS"))
            .exchange()
            .expectStatus().isOk()
            .expectBody(String.class)
            .value(body -> {
                assertTrue(body.contains("Итого: 2000"));
                assertTrue(body.contains(">2</span>"));
            });

        verify(basketService).changeProductCountFromCartPage(USER_ID, 3L, PLUS);
    }

    @Test
    void shouldRenderEmptyCartWithoutBuyButton() {
        when(basketService.getCart(USER_ID)).thenReturn(Mono.just(GetProductCartModelDto.builder()
            .items(List.of())
            .total(BigDecimal.ZERO)
            .build()));

        authenticatedClient.get()
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
    void shouldDisableCheckoutAndExplainInsufficientFunds() {
        when(basketService.getCart(USER_ID)).thenReturn(Mono.just(
            cart(product(3L, "Монитор", 1), BigDecimal.valueOf(2000))
        ));
        when(paymentGateway.getBalance(PAYMENT_ACCOUNT_ID)).thenReturn(Mono.just(BigDecimal.valueOf(1000)));

        authenticatedClient.get()
            .uri("/cart/items")
            .exchange()
            .expectStatus().isOk()
            .expectBody(String.class)
            .value(body -> {
                assertTrue(body.contains("Недостаточно средств"));
                assertTrue(body.contains("доступно 1000"));
                assertTrue(body.contains("disabled"));
            });
    }

    @Test
    void shouldDisableCheckoutWhenPaymentServiceIsUnavailable() {
        when(basketService.getCart(USER_ID)).thenReturn(Mono.just(
            cart(product(3L, "Монитор", 1), BigDecimal.valueOf(2000))
        ));
        when(paymentGateway.getBalance(PAYMENT_ACCOUNT_ID)).thenReturn(Mono.error(
            new PaymentServiceUnavailableException("offline")
        ));

        authenticatedClient.get()
            .uri("/cart/items")
            .exchange()
            .expectStatus().isOk()
            .expectBody(String.class)
            .value(body -> {
                assertTrue(body.contains("Сервис платежей недоступен"));
                assertTrue(body.contains("disabled"));
            });
    }

    @Test
    void shouldRecheckBalanceAfterTransientCheckoutFailure() {
        when(basketService.getCart(USER_ID)).thenReturn(Mono.just(
            cart(product(3L, "Монитор", 1), BigDecimal.valueOf(2000))
        ));
        when(paymentGateway.getBalance(PAYMENT_ACCOUNT_ID)).thenReturn(Mono.just(BigDecimal.valueOf(3000)));

        authenticatedClient.get()
            .uri("/cart/items?paymentError=SERVICE_UNAVAILABLE")
            .exchange()
            .expectStatus().isOk()
            .expectBody(String.class)
            .value(body -> {
                assertTrue(body.contains("На счёте достаточно средств"));
                assertTrue(body.contains("action=\"/buy\""));
            });
    }

    @Test
    void shouldRenderCartMutationError() {
        when(basketService.changeProductCountFromCartPage(USER_ID, 3L, DELETE))
            .thenReturn(Mono.error(new NotRemoveItemException("Товара нет в корзине")));

        authenticatedClient.post()
            .uri("/cart/items?id=3&action=DELETE")
            .exchange()
            .expectStatus().isBadRequest()
            .expectBody(String.class)
            .value(body -> assertTrue(body.contains("Товара нет в корзине")));
    }

    @Test
    void shouldRenderOrdersAndOrderDetails() {
        var order = order(10L);
        when(orderService.getOrders(USER_ID)).thenReturn(Mono.just(
            GetListOrderModelDto.builder().orders(List.of(order)).build()
        ));
        when(orderService.getOrder(USER_ID, 10L)).thenReturn(Mono.just(order));

        authenticatedClient.get()
            .uri("/orders")
            .exchange()
            .expectStatus().isOk()
            .expectBody(String.class)
            .value(body -> {
                assertTrue(body.contains("Заказ №10"));
                assertTrue(body.contains("/orders/10"));
                assertTrue(body.contains("Ноутбук"));
            });

        authenticatedClient.get()
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
        when(orderService.getOrder(USER_ID, 11L)).thenReturn(Mono.just(order(11L)));
        when(orderService.getOrder(USER_ID, 99L))
            .thenReturn(Mono.error(new NoSuchElementException("Заказ не найден")));

        authenticatedClient.get()
            .uri("/orders/11")
            .exchange()
            .expectStatus().isOk()
            .expectBody(String.class)
            .value(body -> assertTrue(!body.contains("Успешная покупка")));

        authenticatedClient.get()
            .uri("/orders/99")
            .exchange()
            .expectStatus().isNotFound()
            .expectBody(String.class)
            .value(body -> assertTrue(body.contains("Заказ не найден")));
    }

    @Test
    void shouldBuyAndRedirectToCreatedOrder() {
        when(orderService.completeOrder(USER_ID, PAYMENT_ACCOUNT_ID)).thenReturn(Mono.just(42L));

        authenticatedClient.post()
            .uri("/buy")
            .exchange()
            .expectStatus().is3xxRedirection()
            .expectHeader().valueEquals("Location", "/orders/42?newOrder=true");

        verify(orderService).completeOrder(USER_ID, PAYMENT_ACCOUNT_ID);
    }

    @Test
    void shouldReturnToCartWhenPaymentFails() {
        when(orderService.completeOrder(USER_ID, PAYMENT_ACCOUNT_ID))
            .thenReturn(Mono.error(new InsufficientFundsException(
                BigDecimal.valueOf(500),
                BigDecimal.valueOf(1000)
            )))
            .thenReturn(Mono.error(new PaymentServiceUnavailableException("offline")))
            .thenReturn(Mono.error(new PaymentRejectedException("conflict")));

        authenticatedClient.post()
            .uri("/buy")
            .exchange()
            .expectStatus().is3xxRedirection()
            .expectHeader().valueEquals(
                "Location",
                "/cart/items?paymentError=INSUFFICIENT_FUNDS"
            );

        authenticatedClient.post()
            .uri("/buy")
            .exchange()
            .expectStatus().is3xxRedirection()
            .expectHeader().valueEquals(
                "Location",
                "/cart/items?paymentError=SERVICE_UNAVAILABLE"
            );

        authenticatedClient.post()
            .uri("/buy")
            .exchange()
            .expectStatus().is3xxRedirection()
            .expectHeader().valueEquals(
                "Location",
                "/cart/items?paymentError=PAYMENT_REJECTED"
            );
    }

    @Test
    void shouldRenderRejectedPaymentWithoutCallingBalanceAgain() {
        when(basketService.getCart(USER_ID)).thenReturn(Mono.just(
            cart(product(3L, "Монитор", 1), BigDecimal.valueOf(2000))
        ));

        authenticatedClient.get()
            .uri("/cart/items?paymentError=PAYMENT_REJECTED")
            .exchange()
            .expectStatus().isOk()
            .expectBody(String.class)
            .value(body -> {
                assertTrue(body.contains("Сервис платежей отклонил запрос"));
                assertTrue(body.contains("disabled"));
            });
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
