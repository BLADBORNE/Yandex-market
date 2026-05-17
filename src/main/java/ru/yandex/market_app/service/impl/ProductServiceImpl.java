package ru.yandex.market_app.service.impl;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.yandex.market_app.dto.GetProductModelDto;
import ru.yandex.market_app.dto.ProductResultDto;
import ru.yandex.market_app.exception.ItemNotFoundException;
import ru.yandex.market_app.mapper.MarketMapper;
import ru.yandex.market_app.util.ProductPageableUtil;
import ru.yandex.market_app.repository.ProductRepository;
import ru.yandex.market_app.service.ProductService;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ProductServiceImpl implements ProductService {

    private static final int TOTAL_PRODUCT_IN_LINE = 3;

    private final ProductRepository productRepository;

    private final MarketMapper marketMapper;

    @Transactional(readOnly = true)
    @Override
    public GetProductModelDto getProducts(
        @Nullable String search,
        @NonNull ProductPageableUtil.ProductSort sort,
        @NonNull Pageable pageable
    ) {
        Slice<ProductResultDto> result = productRepository.getProductsBySearch(search == null ? "" : search, pageable);
        List<ProductResultDto> resultObjects = result.getContent();

        if (CollectionUtils.isEmpty(resultObjects)) {
            return GetProductModelDto.emptyResult(search, sort);
        }

        List<List<ProductResultDto>> products = new ArrayList<>();
        List<ProductResultDto> resultList = new ArrayList<>();

        for (ProductResultDto product : resultObjects) {
            if (resultList.size() == TOTAL_PRODUCT_IN_LINE) {
                products.add(resultList);
                resultList = new ArrayList<>();
            }

            resultList.add(product);
        }

        products.add(resultList);

        List<ProductResultDto> lasLineList = products.getLast();
        while (TOTAL_PRODUCT_IN_LINE > lasLineList.size()) {
            lasLineList.add(ProductResultDto.builder().id(-1L).build());
        }

        return marketMapper.toGetProductModelDto(products, search, sort.name(), result);
    }

    @Transactional(readOnly = true)
    @Override
    public ProductResultDto getItem(Long itemId) {

        return productRepository.getItem(itemId).orElseThrow(() -> new ItemNotFoundException("Не найден объект с id = %d".formatted(itemId)));
    }
}
