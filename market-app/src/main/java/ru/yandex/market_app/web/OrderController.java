package ru.yandex.market_app.web;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import reactor.core.publisher.Mono;
import ru.yandex.market_app.payment.InsufficientFundsException;
import ru.yandex.market_app.payment.PaymentRejectedException;
import ru.yandex.market_app.payment.PaymentServiceUnavailableException;
import ru.yandex.market_app.service.OrderService;
import ru.yandex.market_app.util.RedirectUrlUtil;
import ru.yandex.market_app.util.TemplateAttributeNameUtil;
import ru.yandex.market_app.util.TemplateNameUtil;

@Controller
@RequiredArgsConstructor
public final class OrderController {

    private final OrderService orderService;

    @GetMapping("/orders")
    public Mono<String> getOrders(Model model) {
        return orderService.getOrders()
            .map(result -> {
                model.addAttribute(TemplateAttributeNameUtil.ORDERS, result.orders());
                return TemplateNameUtil.ORDERS;
            });
    }

    @GetMapping("/orders/{id}")
    public Mono<String> getOrder(
        @PathVariable Long id,
        @RequestParam(defaultValue = "false") Boolean newOrder,
        Model model
    ) {
        return orderService.getOrder(id)
            .map(result -> {
                model.addAttribute(TemplateAttributeNameUtil.ORDER, result);
                model.addAttribute(TemplateAttributeNameUtil.NEW_ORDER, newOrder);
                return TemplateNameUtil.ORDER;
            });
    }

    @PostMapping("/buy")
    public Mono<String> completeOrder() {
        return orderService.completeOrder()
            .map(orderId -> RedirectUrlUtil.AFTER_BUY_PAGE.formatted(orderId))
            .onErrorResume(
                InsufficientFundsException.class,
                error -> Mono.just("redirect:/cart/items?paymentError=INSUFFICIENT_FUNDS")
            )
            .onErrorResume(
                PaymentServiceUnavailableException.class,
                error -> Mono.just("redirect:/cart/items?paymentError=SERVICE_UNAVAILABLE")
            )
            .onErrorResume(
                PaymentRejectedException.class,
                error -> Mono.just("redirect:/cart/items?paymentError=PAYMENT_REJECTED")
            );
    }
}
