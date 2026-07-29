package ru.yandex.market_app.model;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@Getter
public enum ProductAction {

    PLUS,

    MINUS,

    DELETE;

    public static final Integer ONE_PRODUCT_VALUE = 1;

    public static final Integer ZERO_PRODUCT_VALUE = 0;
}
