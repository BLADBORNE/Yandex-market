package ru.yandex.market_app.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import ru.yandex.market_app.dto.GetProductCartModelDto;
import ru.yandex.market_app.dto.ProductResultDto;
import ru.yandex.market_app.service.BasketService;

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
import static ru.yandex.market_app.model.ProductAction.DELETE;

@WebMvcTest(BasketController.class)
class BasketControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private BasketService basketService;

    @Test
    void shouldGetCartAndAddItemsAndTotalToModel() throws Exception {
        var items = List.of(
            ProductResultDto.builder()
                .id(3L)
                .title("Монитор")
                .price(BigDecimal.valueOf(25000))
                .count(2)
                .build()
        );
        var total = BigDecimal.valueOf(50000);
        when(basketService.getCart()).thenReturn(
            GetProductCartModelDto.builder()
                .items(items)
                .total(total)
                .build()
        );

        mockMvc.perform(get("/cart/items"))
            .andExpect(status().isOk())
            .andExpect(view().name("cart"))
            .andExpect(model().attribute("items", items))
            .andExpect(model().attribute("total", total));

        verify(basketService).getCart();
    }

    @Test
    void shouldMapParametersAndRedirectAfterChangingCartItem() throws Exception {
        mockMvc.perform(
                post("/cart/items")
                    .param("id", "3")
                    .param("action", DELETE.name())
            )
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/cart/items"));

        verify(basketService).changeProductCountFromCartPage(3L, DELETE);
    }

    @Test
    void shouldReturnBadRequestWhenIdParameterIsNotANumber() throws Exception {
        mockMvc.perform(
                post("/cart/items")
                    .param("id", "not-a-number")
                    .param("action", DELETE.name())
            )
            .andExpect(status().isBadRequest());
    }
}
