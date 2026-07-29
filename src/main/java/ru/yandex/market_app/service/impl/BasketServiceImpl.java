package ru.yandex.market_app.service.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.yandex.market_app.dto.BasketDto;
import ru.yandex.market_app.dto.GetProductCartModelDto;
import ru.yandex.market_app.dto.ProductCartResultDto;
import ru.yandex.market_app.dto.ProductResultDto;
import ru.yandex.market_app.exception.NotRemoveItemException;
import ru.yandex.market_app.exception.OperationNotSupportedException;
import ru.yandex.market_app.mapper.MarketMapper;
import ru.yandex.market_app.model.ProductAction;
import ru.yandex.market_app.model.Product;
import ru.yandex.market_app.model.Basket;
import ru.yandex.market_app.model.BasketProduct;
import ru.yandex.market_app.repository.BasketRepository;
import ru.yandex.market_app.repository.ProductRepository;
import ru.yandex.market_app.service.BasketService;

import java.math.BigDecimal;
import java.util.List;
import java.util.NoSuchElementException;

import static ru.yandex.market_app.model.ProductAction.PLUS;
import static ru.yandex.market_app.model.ProductAction.ONE_PRODUCT_VALUE;
import static ru.yandex.market_app.model.ProductAction.ZERO_PRODUCT_VALUE;
import static ru.yandex.market_app.model.ProductAction.DELETE;

@Service
@RequiredArgsConstructor
public class BasketServiceImpl implements BasketService {

    private final BasketRepository basketRepository;

    private final ProductRepository productRepository;

    private final MarketMapper marketMapper;

    @Transactional
    @Override
    public void changeProductCountFromStartPage(@NonNull Long id, @NonNull ProductAction productAction) {
        checkProductActionIsSupported(productAction);
        performChangeProductCount(id, productAction);
    }

    @Transactional
    @Override
    public ProductResultDto changeProductCountFromItemPage(@NonNull Long id, @NonNull ProductAction productAction) {
        checkProductActionIsSupported(productAction);
        return performChangeProductCount(id, productAction);
    }

    @Transactional
    @Override
    public void changeProductCountFromCartPage(@NonNull Long id, @NonNull ProductAction productAction) {
        performChangeProductCount(id, productAction);
    }

    @Transactional(readOnly = true)
    @Override
    public GetProductCartModelDto getCart() {
        List<ProductCartResultDto> products = productRepository.getProductCart();
        var total = products.isEmpty() ? BigDecimal.ZERO : products.getFirst().total();

        return marketMapper.toGetProductCartModelDto(products, total);
    }

    @Transactional(readOnly = true)
    @Override
    public BasketDto findLazyActiveBasket() {

        return basketRepository.findBasketByStatus(Basket.Status.ACTIVE)
            .orElseThrow(() -> new NoSuchElementException("Активная корзина не найдена"));
    }

    @Transactional(readOnly = true)
    @Override
    public Basket getReferenceById(Long id) {
        return basketRepository.getReferenceById(id);
    }

    @Transactional
    @Override
    public void closeActiveBasket() {
        basketRepository.closeActiveBasket();
    }

    private ProductResultDto changeProductCount(
        BasketProduct neededProduct,
        Basket basket,
        ProductAction productAction,
        int indexOfNeededProduct,
        Product product
    ) {
        ProductResultDto.ProductResultDtoBuilder builder = ProductResultDto.builder()
            .id(product.getId())
            .title(product.getTitle())
            .description(product.getDescription())
            .imgPath(product.getImgPath())
            .price(product.getPrice());

        if (neededProduct != null) {
            switch (productAction) {
                case PLUS -> {
                    var count = neededProduct.getCount() + ONE_PRODUCT_VALUE;
                    neededProduct.setCount(count);
                    builder.count(count);
                }
                case MINUS -> {
                    if (neededProduct.getCount().equals(ONE_PRODUCT_VALUE)) {
                        basket.getBasketProducts().remove(indexOfNeededProduct);
                        builder.count(ZERO_PRODUCT_VALUE);
                    } else {
                        var count = neededProduct.getCount() - ONE_PRODUCT_VALUE;
                        neededProduct.setCount(count);
                        builder.count(count);
                    }
                }
                case DELETE -> {
                    basket.getBasketProducts().remove(indexOfNeededProduct);
                    builder.count(ZERO_PRODUCT_VALUE);
                }
            }
        } else {
            if (PLUS.equals(productAction)) {
                basket.getBasketProducts().add(marketMapper.toBasketProduct(product, basket, ONE_PRODUCT_VALUE));
                builder.count(ONE_PRODUCT_VALUE);
            } else {
                throw new NotRemoveItemException("Для продукта, которого нет в корзине разрешена только операция добавления");
            }
        }

        return builder.build();
    }

    private ProductResultDto performChangeProductCount(@NonNull Long id, @NonNull ProductAction productAction) {
        Product product = productRepository.findById(id)
            .orElseThrow(() -> new NoSuchElementException("Отсутствует продукт с id = %d".formatted(id)));
        Basket basket = basketRepository.findEagerBasketByStatus(Basket.Status.ACTIVE).orElse(null);

        if (ProductAction.MINUS.equals(productAction) && basket == null) {
            throw new NotRemoveItemException("Корзина не создана, удаление товаров невозможно");
        }

        if (basket == null) {
            basket = marketMapper.toBasket(Basket.Status.ACTIVE);
        }

        BasketProduct neededProduct = null;
        int indexOfNeededProduct = 0;

        for (var element : basket.getBasketProducts()) {
            if (element.getId().getProductId().equals(id)) {
                neededProduct = element;

                break;
            }

            indexOfNeededProduct++;
        }

        var result = changeProductCount(neededProduct, basket, productAction, indexOfNeededProduct, product);
        basketRepository.save(basket);

        return result;
    }

    private void checkProductActionIsSupported(ProductAction productAction) {

        if (DELETE.equals(productAction)) {
            throw new OperationNotSupportedException("Данный запрос не поддерживает операцию удаления товара");
        }
    }
}
