package ru.yandex.market_app.dto;

import lombok.Builder;
import org.springframework.lang.NonNull;

@Builder
public record PageableResult(Integer pageSize, Integer pageNumber, Boolean hasPrevious, Boolean hasNext) {

    public static PageableResult init(
        @NonNull Integer pageSize,
        @NonNull Integer pageNumber,
        @NonNull Boolean hasPrevious,
        @NonNull Boolean hasNext
    ) {
        return PageableResult.builder()
            .pageSize(pageSize)
            .pageNumber(pageNumber)
            .hasPrevious(hasPrevious)
            .hasNext(hasNext)
            .build();

    }
}
