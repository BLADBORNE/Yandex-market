package ru.yandex.market_app.service.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;
import ru.yandex.market_app.dto.BasketDto;
import ru.yandex.market_app.dto.GetProductCartModelDto;
import ru.yandex.market_app.dto.ProductCount;
import ru.yandex.market_app.dto.ProductResultDto;
import ru.yandex.market_app.exception.NotRemoveItemException;
import ru.yandex.market_app.exception.OperationNotSupportedException;
import ru.yandex.market_app.mapper.MarketMapper;
import ru.yandex.market_app.model.Basket;
import ru.yandex.market_app.model.Product;
import ru.yandex.market_app.model.ProductAction;
import ru.yandex.market_app.repository.BasketProductRepository;
import ru.yandex.market_app.repository.BasketRepository;
import ru.yandex.market_app.repository.ProductRepository;
import ru.yandex.market_app.service.BasketService;
import ru.yandex.market_app.service.ProductCatalogProvider;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.function.Function;
import java.util.stream.Collectors;

import static ru.yandex.market_app.model.ProductAction.DELETE;
import static ru.yandex.market_app.model.ProductAction.MINUS;
import static ru.yandex.market_app.model.ProductAction.PLUS;

@Service
@RequiredArgsConstructor
public class BasketServiceImpl implements BasketService {

    private final BasketRepository basketRepository;
    private final BasketProductRepository basketProductRepository;
    private final ProductRepository productRepository;
    private final ProductCatalogProvider productCatalogProvider;
    private final MarketMapper marketMapper;

    @Transactional
    @Override
    public Mono<Void> changeProductCountFromStartPage(@NonNull Long id, @NonNull ProductAction productAction) {
        return rejectDelete(productAction).then(performChangeProductCount(id, productAction)).then();
    }

    @Transactional
    @Override
    public Mono<ProductResultDto> changeProductCountFromItemPage(
        @NonNull Long id,
        @NonNull ProductAction productAction
    ) {
        return rejectDelete(productAction).then(performChangeProductCount(id, productAction));
    }

    @Transactional
    @Override
    public Mono<Void> changeProductCountFromCartPage(@NonNull Long id, @NonNull ProductAction productAction) {
        return performChangeProductCount(id, productAction).then();
    }

    @Override
    public Mono<GetProductCartModelDto> getCart() {
        return basketProductRepository.findActiveProductCounts()
            .collectList()
            .flatMap(this::hydrateCart);
    }

    private Mono<GetProductCartModelDto> hydrateCart(java.util.List<ProductCount> counts) {
        if (counts.isEmpty()) {
            return Mono.just(GetProductCartModelDto.builder()
                .items(java.util.List.of())
                .total(BigDecimal.ZERO)
                .build());
        }

        var productIds = counts.stream()
            .map(ProductCount::productId)
            .collect(Collectors.toSet());

        return productCatalogProvider.getCatalogContaining(productIds)
            .map(catalog -> {
                Map<Long, ru.yandex.market_app.cache.CachedProduct> productsById = catalog.products().stream()
                    .collect(Collectors.toMap(ru.yandex.market_app.cache.CachedProduct::id, Function.identity()));

                var items = counts.stream()
                    .map(count -> {
                        var product = productsById.get(count.productId());
                        if (product == null) {
                            throw new IllegalStateException(
                                "Товар корзины с id %d не найден в каталоге".formatted(count.productId())
                            );
                        }
                        return marketMapper.toProductResultDto(product, count.count());
                    })
                    .sorted(Comparator.comparing(ProductResultDto::title).thenComparing(ProductResultDto::id))
                    .toList();

                BigDecimal total = items.stream()
                    .map(item -> item.price().multiply(BigDecimal.valueOf(item.count())))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

                return GetProductCartModelDto.builder()
                    .items(items)
                    .total(total)
                    .build();
            });
    }

    @Transactional(readOnly = true)
    @Override
    public Mono<BasketDto> findLazyActiveBasket() {
        return basketRepository.findActiveBasketWithTotal()
            .switchIfEmpty(Mono.error(new NoSuchElementException("Активная корзина не найдена")));
    }

    @Transactional(readOnly = true)
    @Override
    public Mono<Basket> getReferenceById(Long id) {
        return basketRepository.findById(id)
            .switchIfEmpty(Mono.error(new NoSuchElementException("Корзина с id %d не найдена".formatted(id))));
    }

    @Transactional
    @Override
    public Mono<Void> closeActiveBasket(Long basketId) {
        return basketRepository.closeBasket(basketId)
            .filter(Boolean::booleanValue)
            .switchIfEmpty(Mono.error(new NoSuchElementException("Активная корзина не найдена")))
            .then();
    }

    private Mono<ProductResultDto> performChangeProductCount(Long id, ProductAction action) {
        return productRepository.findById(id)
            .switchIfEmpty(Mono.error(
                new NoSuchElementException("Отсутствует продукт с id = %d".formatted(id))
            ))
            .flatMap(product -> selectBasket(action)
                .flatMap(basket -> changeCount(product, basket, action)));
    }

    private Mono<Basket> selectBasket(ProductAction action) {
        if (action == PLUS) {
            return basketRepository.getOrCreateActiveBasket();
        }

        return basketRepository.findActiveBasketForUpdate()
            .switchIfEmpty(Mono.error(
                new NotRemoveItemException("Корзина не создана, удаление товаров невозможно")
            ));
    }

    private Mono<ProductResultDto> changeCount(Product product, Basket basket, ProductAction action) {
        Mono<Integer> count = switch (action) {
            case PLUS -> basketProductRepository.increment(basket.getId(), product.getId());
            case MINUS -> basketProductRepository.decrement(basket.getId(), product.getId());
            case DELETE -> basketProductRepository.delete(basket.getId(), product.getId());
        };

        return count
            .map(updatedCount -> marketMapper.toProductResultDto(product, updatedCount))
            .switchIfEmpty(Mono.error(new NotRemoveItemException(
                "Товар с id %d отсутствует в корзине".formatted(product.getId())
            )));
    }

    private Mono<Void> rejectDelete(ProductAction action) {
        return action == DELETE
            ? Mono.error(new OperationNotSupportedException(
                "Данный запрос не поддерживает операцию удаления товара"
            ))
            : Mono.empty();
    }
}
