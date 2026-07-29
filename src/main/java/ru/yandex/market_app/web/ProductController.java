package ru.yandex.market_app.web;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import ru.yandex.market_app.model.ProductAction;
import ru.yandex.market_app.service.BasketService;
import ru.yandex.market_app.util.ProductPageableUtil;
import ru.yandex.market_app.service.ProductService;
import ru.yandex.market_app.util.RedirectUrlUtil;
import ru.yandex.market_app.util.TemplateAttributeNameUtil;
import ru.yandex.market_app.util.TemplateNameUtil;

@Controller
@RequiredArgsConstructor
public final class ProductController {

    private final ProductService productService;

    private final BasketService basketService;

    @GetMapping("/items")
    public String getProducts(
        @RequestParam(required = false) String search,
        @RequestParam(defaultValue = "NO") ProductPageableUtil.ProductSort sort,
        @PageableDefault(page = 1, size = 5) Pageable pageable,
        Model model
    ) {
        var page = ProductPageableUtil.createPageableBySort(sort, pageable);
        var result = productService.getProducts(search, sort, page);
        model.addAttribute(TemplateAttributeNameUtil.ITEMS, result.items());
        model.addAttribute(TemplateAttributeNameUtil.SEARCH, result.search());
        model.addAttribute(TemplateAttributeNameUtil.SORT, result.sort());
        model.addAttribute(TemplateAttributeNameUtil.PAGING, result.paging());

        return TemplateNameUtil.ITEMS;
    }

    @PostMapping("/items")
    public String changeProductCountFromStartPage(
        @RequestParam Long id,
        @RequestParam(required = false) String search,
        @RequestParam(defaultValue = "NO") ProductPageableUtil.ProductSort sort,
        @PageableDefault(page = 1, size = 5) Pageable pageable,
        @RequestParam ProductAction action
    ) {
        basketService.changeProductCountFromStartPage(id, action);

        return RedirectUrlUtil.TO_HOME_PAGE.formatted(search, sort, pageable.getPageNumber(), pageable.getPageSize());
    }

    @GetMapping("/items/{id}")
    public String getItem(@PathVariable Long id, Model model) {
        model.addAttribute("item", productService.getItem(id));

        return TemplateNameUtil.ITEM;
    }

    @PostMapping("/items/{id}")
    public String changeProductCountFromItemPage(
        @PathVariable Long id,
        @RequestParam ProductAction action,
        Model model
    ) {
        var result = basketService.changeProductCountFromItemPage(id, action);
        model.addAttribute(TemplateAttributeNameUtil.ITEM, result);

        return TemplateNameUtil.ITEM;
    }
}
