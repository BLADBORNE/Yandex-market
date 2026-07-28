package ru.yandex.market_app.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import ru.yandex.market_app.dto.GetListOrderModelDto;
import ru.yandex.market_app.dto.GetOrderModelDto;
import ru.yandex.market_app.dto.ItemDto;
import ru.yandex.market_app.service.OrderService;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@WebMvcTest(OrderController.class)
class OrderControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OrderService orderService;

    @Test
    void shouldGetOrdersAndAddThemToModel() throws Exception {
        var orders = List.of(order(10L));
        when(orderService.getOrders()).thenReturn(GetListOrderModelDto.builder().orders(orders).build());

        mockMvc.perform(get("/orders"))
            .andExpect(status().isOk())
            .andExpect(view().name("orders"))
            .andExpect(model().attribute("orders", orders));

        verify(orderService).getOrders();
    }

    @Test
    void shouldMapOrderIdAndNewOrderParameterAndAddThemToModel() throws Exception {
        var order = order(10L);
        when(orderService.getOrder(10L)).thenReturn(order);

        mockMvc.perform(
                get("/orders/{id}", 10L)
                    .param("newOrder", "true")
            )
            .andExpect(status().isOk())
            .andExpect(view().name("order"))
            .andExpect(model().attribute("order", order))
            .andExpect(model().attribute("newOrder", true));

        verify(orderService).getOrder(10L);
    }

    @Test
    void shouldUseFalseAsDefaultNewOrderParameter() throws Exception {
        var order = order(11L);
        when(orderService.getOrder(11L)).thenReturn(order);

        mockMvc.perform(get("/orders/{id}", 11L))
            .andExpect(status().isOk())
            .andExpect(view().name("order"))
            .andExpect(model().attribute("newOrder", false));
    }

    @Test
    void shouldRedirectToCreatedOrderAfterBuy() throws Exception {
        when(orderService.completeOrder()).thenReturn(42L);

        mockMvc.perform(post("/buy"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/orders/42?newOrder=true"));

        verify(orderService).completeOrder();
    }

    @Test
    void shouldReturnBadRequestWhenOrderIdIsNotANumber() throws Exception {
        mockMvc.perform(get("/orders/not-a-number"))
            .andExpect(status().isBadRequest());
    }

    private GetOrderModelDto order(Long id) {
        var items = List.of(
            ItemDto.builder()
                .id(1L)
                .title("Ноутбук")
                .price(BigDecimal.valueOf(55000))
                .count(1)
                .build()
        );
        return GetOrderModelDto.builder()
            .id(id)
            .items(items)
            .totalSum(BigDecimal.valueOf(55000))
            .build();
    }
}
