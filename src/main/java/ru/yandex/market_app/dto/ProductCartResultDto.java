package ru.yandex.market_app.dto;

import java.math.BigDecimal;

public record ProductCartResultDto(
    Long id,

    String title,

    String description,

    String imgPath,

    BigDecimal price,

    Integer count,

    BigDecimal total
) {
}
