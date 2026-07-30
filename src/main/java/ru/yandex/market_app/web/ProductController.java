package ru.yandex.market_app.web;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import reactor.core.publisher.Mono;
import ru.yandex.market_app.dto.ActionRequest;
import ru.yandex.market_app.dto.CatalogActionRequest;
import ru.yandex.market_app.model.ProductAction;
import ru.yandex.market_app.service.BasketService;
import ru.yandex.market_app.service.ProductService;
import ru.yandex.market_app.util.ProductPageableUtil;
import ru.yandex.market_app.util.RedirectUrlUtil;
import ru.yandex.market_app.util.TemplateAttributeNameUtil;
import ru.yandex.market_app.util.TemplateNameUtil;

@Controller
@Validated
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;
    private final BasketService basketService;

    @GetMapping({"/", "/items"})
    public Mono<String> getProducts(
        @RequestParam(defaultValue = "") String search,
        @RequestParam(defaultValue = "NO") ProductPageableUtil.ProductSort sort,
        @RequestParam(defaultValue = "1") @Min(1) int pageNumber,
        @RequestParam(defaultValue = "5") @Min(1) @Max(100) int pageSize,
        Model model
    ) {
        var page = ProductPageableUtil.createPageableBySort(sort, pageNumber, pageSize);

        return productService.getProducts(search, sort, page)
            .map(result -> {
                model.addAttribute(TemplateAttributeNameUtil.ITEMS, result.items());
                model.addAttribute(TemplateAttributeNameUtil.SEARCH, result.search());
                model.addAttribute(TemplateAttributeNameUtil.SORT, result.sort());
                model.addAttribute(TemplateAttributeNameUtil.PAGING, result.paging());
                return TemplateNameUtil.ITEMS;
            });
    }

    @PostMapping("/items")
    public Mono<String> changeProductCountFromStartPage(
        @Valid @ModelAttribute CatalogActionRequest request
    ) {
        return basketService.changeProductCountFromStartPage(request.getId(), request.getAction())
            .thenReturn(RedirectUrlUtil.toHomePage(
                request.getSearch(),
                request.getSort(),
                request.getPageNumber(),
                request.getPageSize()
            ));
    }

    @GetMapping("/items/{id}")
    public Mono<String> getItem(@PathVariable Long id, Model model) {
        return productService.getItem(id)
            .map(item -> {
                model.addAttribute(TemplateAttributeNameUtil.ITEM, item);
                return TemplateNameUtil.ITEM;
            });
    }

    @PostMapping("/items/{id}")
    public Mono<String> changeProductCountFromItemPage(
        @PathVariable Long id,
        @Valid @ModelAttribute ActionRequest request,
        Model model
    ) {
        return basketService.changeProductCountFromItemPage(id, request.getAction())
            .map(item -> {
                model.addAttribute(TemplateAttributeNameUtil.ITEM, item);
                return TemplateNameUtil.ITEM;
            });
    }
}
