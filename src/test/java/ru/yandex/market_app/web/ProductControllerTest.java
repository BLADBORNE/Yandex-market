package ru.yandex.market_app.web;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import ru.yandex.market_app.dto.GetProductModelDto;
import ru.yandex.market_app.dto.PageableResult;
import ru.yandex.market_app.dto.ProductResultDto;
import ru.yandex.market_app.service.BasketService;
import ru.yandex.market_app.service.ProductService;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;
import static ru.yandex.market_app.model.ProductAction.MINUS;
import static ru.yandex.market_app.model.ProductAction.PLUS;
import static ru.yandex.market_app.util.ProductPageableUtil.ProductSort.PRICE;

@WebMvcTest(ProductController.class)
class ProductControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ProductService productService;

    @MockitoBean
    private BasketService basketService;

    @Test
    void shouldGetProductsWithMappedParametersAndModelAttributes() throws Exception {
        var products = List.of(List.of(product(1L, "Ноутбук", 2)));
        var paging = PageableResult.init(7, 1, true, true);
        var result = GetProductModelDto.builder()
            .items(products)
            .search("ноутбук")
            .sort(PRICE.name())
            .paging(paging)
            .build();
        when(productService.getProducts(eq("ноутбук"), eq(PRICE), any(Pageable.class)))
            .thenReturn(result);

        mockMvc.perform(
                get("/items")
                    .param("search", "ноутбук")
                    .param("sort", PRICE.name())
                    .param("pageNumber", "2")
                    .param("pageSize", "7")
            )
            .andExpect(status().isOk())
            .andExpect(view().name("items"))
            .andExpect(model().attribute("items", products))
            .andExpect(model().attribute("search", "ноутбук"))
            .andExpect(model().attribute("sort", PRICE.name()))
            .andExpect(model().attribute("paging", paging));

        var pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(productService).getProducts(eq("ноутбук"), eq(PRICE), pageableCaptor.capture());
        var pageable = pageableCaptor.getValue();
        assertEquals(1, pageable.getPageNumber());
        assertEquals(7, pageable.getPageSize());
        var priceOrder = pageable.getSort().getOrderFor("price");
        assertNotNull(priceOrder);
        assertEquals(Sort.Direction.ASC, priceOrder.getDirection());
    }

    @Test
    void shouldRedirectAfterChangingProductCountFromStartPage() throws Exception {
        mockMvc.perform(
                post("/items")
                    .param("id", "15")
                    .param("search", "phone")
                    .param("sort", PRICE.name())
                    .param("pageNumber", "3")
                    .param("pageSize", "10")
                    .param("action", PLUS.name())
            )
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/items?search=phone&sort=PRICE&pageNumber=3&pageSize=10"));

        verify(basketService).changeProductCountFromStartPage(15L, PLUS);
    }

    @Test
    void shouldGetItemByPathVariableAndAddItToModel() throws Exception {
        var item = product(7L, "Телефон", 1);
        when(productService.getItem(7L)).thenReturn(item);

        mockMvc.perform(get("/items/{id}", 7L))
            .andExpect(status().isOk())
            .andExpect(view().name("item"))
            .andExpect(model().attribute("item", item));

        verify(productService).getItem(7L);
    }

    @Test
    void shouldChangeProductCountFromItemPageAndAddUpdatedItemToModel() throws Exception {
        var item = product(7L, "Телефон", 0);
        when(basketService.changeProductCountFromItemPage(7L, MINUS)).thenReturn(item);

        mockMvc.perform(
                post("/items/{id}", 7L)
                    .param("action", MINUS.name())
            )
            .andExpect(status().isOk())
            .andExpect(view().name("item"))
            .andExpect(model().attribute("item", item));

        verify(basketService).changeProductCountFromItemPage(7L, MINUS);
    }

    @Test
    void shouldReturnBadRequestWhenActionParameterIsMissing() throws Exception {
        mockMvc.perform(post("/items/{id}", 7L))
            .andExpect(status().isBadRequest());
    }

    private ProductResultDto product(Long id, String title, Integer count) {
        return ProductResultDto.builder()
            .id(id)
            .title(title)
            .description("Описание")
            .imgPath("images/product.jpg")
            .price(BigDecimal.valueOf(1000))
            .count(count)
            .build();
    }
}
