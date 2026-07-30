package ru.yandex.market_app.web;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import reactor.core.publisher.Mono;
import ru.yandex.market_app.dto.CartActionRequest;
import ru.yandex.market_app.dto.GetProductCartModelDto;
import ru.yandex.market_app.model.ProductAction;
import ru.yandex.market_app.service.BasketService;
import ru.yandex.market_app.util.TemplateAttributeNameUtil;
import ru.yandex.market_app.util.TemplateNameUtil;

@Controller
@RequiredArgsConstructor
public final class BasketController {

    private final BasketService basketService;

    @GetMapping("/cart/items")
    public Mono<String> getCart(Model model) {
        return renderCart(model);
    }

    @PostMapping("/cart/items")
    public Mono<String> changeProductCountFromCartPage(
        @Valid @ModelAttribute CartActionRequest request,
        Model model
    ) {
        return basketService.changeProductCountFromCartPage(request.getId(), request.getAction())
            .then(Mono.defer(() -> renderCart(model)));
    }

    private Mono<String> renderCart(Model model) {
        return basketService.getCart()
            .map(cart -> addCartToModel(cart, model));
    }

    private String addCartToModel(GetProductCartModelDto cart, Model model) {
        model.addAttribute(TemplateAttributeNameUtil.ITEMS, cart.items());
        model.addAttribute(TemplateAttributeNameUtil.TOTAL, cart.total());
        return TemplateNameUtil.CART;
    }
}
