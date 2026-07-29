package ru.yandex.market_app.util;

import lombok.experimental.UtilityClass;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

@UtilityClass
public class ProductPageableUtil {

    public Pageable createPageableBySort(ProductSort sort, int pageNumber, int pageSize) {
        int zeroBasedPage = pageNumber - 1;

        return switch (sort) {

            case NO -> PageRequest.of(zeroBasedPage, pageSize);
            case ALPHA -> PageRequest.of(zeroBasedPage, pageSize, Sort.Direction.ASC, "title");
            case PRICE -> PageRequest.of(zeroBasedPage, pageSize, Sort.Direction.ASC, "price");
        };
    }

    public enum ProductSort {

        NO,

        ALPHA,

        PRICE
    }
}
