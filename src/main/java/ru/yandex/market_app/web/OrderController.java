package ru.yandex.market_app.web;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import ru.yandex.market_app.service.OrderService;
import ru.yandex.market_app.util.RedirectUrlUtil;
import ru.yandex.market_app.util.TemplateAttributeNameUtil;
import ru.yandex.market_app.util.TemplateNameUtil;

@Controller
@RequiredArgsConstructor
public final class OrderController {

    private final OrderService orderService;

    @GetMapping("/orders")
    public String getOrders(Model model) {
        var result = orderService.getOrders();
        model.addAttribute(TemplateAttributeNameUtil.ORDERS, result.orders());

        return TemplateNameUtil.ORDERS;
    }

    @GetMapping("/orders/{id}")
    public String getOrder(
        @PathVariable Long id,
        @RequestParam(defaultValue = "false") Boolean newOrder,
        Model model
    ) {
        var result = orderService.getOrder(id);
        model.addAttribute(TemplateAttributeNameUtil.ORDER, result);
        model.addAttribute(TemplateAttributeNameUtil.NEW_ORDER, newOrder);

        return TemplateNameUtil.ORDER;
    }

    @PostMapping("/buy")
    public String completeOrder() {
        var result = orderService.completeOrder();

        return RedirectUrlUtil.AFTER_BUY_PAGE.formatted(result);
    }
}
