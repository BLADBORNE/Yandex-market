package ru.yandex.market_app.util;

import lombok.experimental.UtilityClass;

@UtilityClass
public class RedirectUrlUtil {

    public final String TO_HOME_PAGE = "redirect:/items?search=%s&sort=%s&pageNumber=%d&pageSize=%d";

    public final String AFTER_BUY_PAGE = "redirect:/orders/%d?newOrder=true";

    public final String TO_BASKET_AFTER_OPERATION_WITH_PRODUCT = "redirect:/cart/items";
}
