package ru.yandex.market_app.util;

import lombok.experimental.UtilityClass;
import org.springframework.web.util.UriComponentsBuilder;

import java.nio.charset.StandardCharsets;

@UtilityClass
public class RedirectUrlUtil {

    public final String AFTER_BUY_PAGE = "redirect:/orders/%d?newOrder=true";

    public String toHomePage(
        String search,
        ProductPageableUtil.ProductSort sort,
        int pageNumber,
        int pageSize
    ) {
        String location = UriComponentsBuilder.fromPath("/items")
            .queryParam("search", search)
            .queryParam("sort", sort)
            .queryParam("pageNumber", pageNumber)
            .queryParam("pageSize", pageSize)
            .build()
            .encode(StandardCharsets.UTF_8)
            .toUriString();

        return "redirect:" + location;
    }
}
