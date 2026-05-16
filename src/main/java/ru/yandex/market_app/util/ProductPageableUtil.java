package ru.yandex.market_app.util;

import lombok.experimental.UtilityClass;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

@UtilityClass
public class ProductPageableUtil {

    public Pageable createPageableBySort(ProductSort sort, Pageable pageable) {

        return switch (sort) {

            case NO -> PageRequest.of(pageable.getPageNumber() - 1, pageable.getPageSize());

            case ALPHA -> {

                Sort updatedSort = Sort.by(Sort.Direction.ASC, "description");

                yield PageRequest.of(pageable.getPageNumber() - 1, pageable.getPageSize(), updatedSort);
            }

            case PRICE -> {

                Sort updatedSort = Sort.by(Sort.Direction.ASC, "price");

                yield PageRequest.of(pageable.getPageNumber() - 1, pageable.getPageSize(), updatedSort);
            }
        };
    }

    public enum ProductSort {

        NO,

        ALPHA,

        PRICE
    }
}
