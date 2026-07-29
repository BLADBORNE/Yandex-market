package ru.yandex.market_app.service.impl;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;
import ru.yandex.market_app.dto.GetProductModelDto;
import ru.yandex.market_app.dto.PageableResult;
import ru.yandex.market_app.dto.ProductResultDto;
import ru.yandex.market_app.exception.ItemNotFoundException;
import ru.yandex.market_app.repository.ProductRepository;
import ru.yandex.market_app.service.ProductService;
import ru.yandex.market_app.util.ProductPageableUtil;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ProductServiceImpl implements ProductService {

    private static final int PRODUCTS_IN_ROW = 3;

    private final ProductRepository productRepository;

    @Transactional(readOnly = true)
    @Override
    public Mono<GetProductModelDto> getProducts(
        @Nullable String search,
        @NonNull ProductPageableUtil.ProductSort sort,
        @NonNull Pageable pageable
    ) {
        String normalizedSearch = search == null ? "" : search.trim();
        int pageSize = pageable.getPageSize();
        long offset = pageable.getOffset();

        return productRepository.findCatalogPage(normalizedSearch, sort, pageSize + 1, offset)
            .collectList()
            .map(products -> toModel(products, normalizedSearch, sort, pageable));
    }

    @Transactional(readOnly = true)
    @Override
    public Mono<ProductResultDto> getItem(Long itemId) {
        return productRepository.findCatalogItem(itemId)
            .switchIfEmpty(Mono.error(
                new ItemNotFoundException("Не найден объект с id = %d".formatted(itemId))
            ));
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
