package ru.yandex.market_app.service.impl;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import ru.yandex.market_app.cache.CachedProduct;
import ru.yandex.market_app.dto.GetProductModelDto;
import ru.yandex.market_app.dto.PageableResult;
import ru.yandex.market_app.dto.ProductResultDto;
import ru.yandex.market_app.exception.ItemNotFoundException;
import ru.yandex.market_app.mapper.MarketMapper;
import ru.yandex.market_app.repository.BasketProductRepository;
import ru.yandex.market_app.service.ProductCatalogProvider;
import ru.yandex.market_app.service.ProductService;
import ru.yandex.market_app.util.ProductPageableUtil;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

@Service
@RequiredArgsConstructor
public class ProductServiceImpl implements ProductService {

    private static final int PRODUCTS_IN_ROW = 3;

    private final ProductCatalogProvider productCatalogProvider;
    private final BasketProductRepository basketProductRepository;
    private final MarketMapper marketMapper;

    @Override
    public Mono<GetProductModelDto> getProducts(
        @Nullable String search,
        @NonNull ProductPageableUtil.ProductSort sort,
        @NonNull Pageable pageable
    ) {
        String normalizedSearch = search == null ? "" : search.trim();
        return Mono.zip(
                productCatalogProvider.getCatalog(),
                basketProductRepository.findActiveProductCounts()
                    .collectMap(count -> count.productId(), count -> count.count())
            )
            .map(tuple -> tuple.getT1().products().stream()
                .filter(matches(normalizedSearch))
                .sorted(comparator(sort))
                .skip(pageable.getOffset())
                .limit((long) pageable.getPageSize() + 1)
                .map(product -> marketMapper.toProductResultDto(
                    product,
                    tuple.getT2().getOrDefault(product.id(), 0)
                ))
                .toList())
            .map(products -> toModel(products, normalizedSearch, sort, pageable));
    }

    @Override
    public Mono<ProductResultDto> getItem(Long itemId) {
        return productCatalogProvider.getProduct(itemId)
            .switchIfEmpty(Mono.error(
                new ItemNotFoundException("Не найден объект с id = %d".formatted(itemId))
            ))
            .zipWith(basketProductRepository.findActiveProductCount(itemId).defaultIfEmpty(0))
            .map(tuple -> marketMapper.toProductResultDto(tuple.getT1(), tuple.getT2()));
    }

    private Predicate<CachedProduct> matches(String search) {
        String normalizedSearch = search.toLowerCase(Locale.ROOT);
        if (normalizedSearch.isEmpty()) {
            return product -> true;
        }

        return product -> product.title().toLowerCase(Locale.ROOT).contains(normalizedSearch)
            || product.description().toLowerCase(Locale.ROOT).contains(normalizedSearch);
    }

    private Comparator<CachedProduct> comparator(ProductPageableUtil.ProductSort sort) {
        Comparator<CachedProduct> byId = Comparator.comparing(CachedProduct::id);

        return switch (sort) {
            case NO -> byId;
            case ALPHA -> Comparator
                .comparing((CachedProduct product) -> product.title().toLowerCase(Locale.ROOT))
                .thenComparing(byId);
            case PRICE -> Comparator.comparing(CachedProduct::price).thenComparing(byId);
        };
    }

    private GetProductModelDto toModel(
        List<ProductResultDto> fetchedProducts,
        String search,
        ProductPageableUtil.ProductSort sort,
        Pageable pageable
    ) {
        boolean hasNext = fetchedProducts.size() > pageable.getPageSize();
        List<ProductResultDto> pageProducts = hasNext
            ? fetchedProducts.subList(0, pageable.getPageSize())
            : fetchedProducts;

        List<List<ProductResultDto>> rows = new ArrayList<>();
        for (int start = 0; start < pageProducts.size(); start += PRODUCTS_IN_ROW) {
            int end = Math.min(start + PRODUCTS_IN_ROW, pageProducts.size());
            rows.add(new ArrayList<>(pageProducts.subList(start, end)));
        }

        if (!rows.isEmpty()) {
            List<ProductResultDto> lastRow = rows.getLast();
            while (lastRow.size() < PRODUCTS_IN_ROW) {
                lastRow.add(ProductResultDto.builder().id(-1L).build());
            }
        }

        return GetProductModelDto.builder()
            .items(rows)
            .search(search)
            .sort(sort.name())
            .paging(PageableResult.init(
                pageable.getPageSize(),
                pageable.getPageNumber() + 1,
                pageable.hasPrevious(),
                hasNext
            ))
            .build();
    }
}
