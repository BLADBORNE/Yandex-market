package ru.yandex.market_app.dto;

import lombok.Builder;

import java.math.BigDecimal;

@Builder
public record ProductResultDto(
    Long id,

    String title,

    String description,

    String imgPath,

    BigDecimal price,

    Integer count
) {
}
