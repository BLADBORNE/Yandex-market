package ru.yandex.market_app.web;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import ru.yandex.market_app.model.ProductAction;
import ru.yandex.market_app.service.BasketService;
import ru.yandex.market_app.util.RedirectUrlUtil;
import ru.yandex.market_app.util.TemplateAttributeNameUtil;
import ru.yandex.market_app.util.TemplateNameUtil;

@Controller
@RequiredArgsConstructor
public final class BasketController {

    private final BasketService basketService;

    @GetMapping("/cart/items")
    public String getCart(Model model) {
        var result = basketService.getCart();
        model.addAttribute(TemplateAttributeNameUtil.ITEMS, result.items());
        model.addAttribute(TemplateAttributeNameUtil.TOTAL, result.total());

        return TemplateNameUtil.CART;
    }

    @PostMapping("/cart/items")
    public String changeProductCountFromCartPage(@RequestParam Long id, @RequestParam ProductAction action) {
        basketService.changeProductCountFromCartPage(id, action);

        return RedirectUrlUtil.TO_BASKET_AFTER_OPERATION_WITH_PRODUCT;
    }
}
