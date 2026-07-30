package ru.yandex.market_app.web;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import reactor.core.publisher.Mono;
import ru.yandex.market_app.dto.CartPaymentState;
import ru.yandex.market_app.dto.CartActionRequest;
import ru.yandex.market_app.dto.GetProductCartModelDto;
import ru.yandex.market_app.payment.PaymentGateway;
import ru.yandex.market_app.payment.PaymentServiceUnavailableException;
import ru.yandex.market_app.service.BasketService;
import ru.yandex.market_app.util.TemplateAttributeNameUtil;
import ru.yandex.market_app.util.TemplateNameUtil;

@Controller
@RequiredArgsConstructor
public final class BasketController {

    private final BasketService basketService;
    private final PaymentGateway paymentGateway;

    @GetMapping("/cart/items")
    public Mono<String> getCart(
        @RequestParam(required = false) String paymentError,
        Model model
    ) {
        return renderCart(model, paymentError);
    }

    @PostMapping("/cart/items")
    public Mono<String> changeProductCountFromCartPage(
        @Valid @ModelAttribute CartActionRequest request,
        Model model
    ) {
        return basketService.changeProductCountFromCartPage(request.getId(), request.getAction())
            .then(Mono.defer(() -> renderCart(model, null)));
    }

    private Mono<String> renderCart(Model model, String paymentError) {
        return basketService.getCart()
            .flatMap(cart -> getPaymentState(cart, paymentError)
                .map(paymentState -> addCartToModel(cart, paymentState, model)));
    }

    private Mono<CartPaymentState> getPaymentState(
        GetProductCartModelDto cart,
        String paymentError
    ) {
        if (cart.items().isEmpty()) {
            return Mono.just(CartPaymentState.empty());
        }

        if ("PAYMENT_REJECTED".equals(paymentError)) {
            return Mono.just(CartPaymentState.rejected());
        }

        return paymentGateway.getBalance()
            .map(balance -> balance.compareTo(cart.total()) >= 0
                ? CartPaymentState.available(balance)
                : CartPaymentState.insufficient(balance, cart.total()))
            .map(state -> "INSUFFICIENT_FUNDS".equals(paymentError)
                && state.status() == ru.yandex.market_app.dto.CheckoutStatus.AVAILABLE
                ? CartPaymentState.insufficientAfterPaymentAttempt()
                : state)
            .onErrorResume(
                PaymentServiceUnavailableException.class,
                error -> Mono.just(CartPaymentState.unavailable())
            );
    }

    private String addCartToModel(
        GetProductCartModelDto cart,
        CartPaymentState paymentState,
        Model model
    ) {
        model.addAttribute(TemplateAttributeNameUtil.ITEMS, cart.items());
        model.addAttribute(TemplateAttributeNameUtil.TOTAL, cart.total());
        model.addAttribute(TemplateAttributeNameUtil.CAN_BUY, paymentState.canBuy());
        model.addAttribute(TemplateAttributeNameUtil.PAYMENT_MESSAGE, paymentState.message());
        model.addAttribute(TemplateAttributeNameUtil.PAYMENT_STATUS, paymentState.status());
        return TemplateNameUtil.CART;
    }
}
