package ru.yandex.market_app.repository;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import ru.yandex.market_app.dto.ProductCartResultDto;
import ru.yandex.market_app.dto.ProductResultDto;
import ru.yandex.market_app.model.Product;
import ru.yandex.market_app.util.SqlUtil;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProductRepository extends JpaRepository<Product, Long> {

    @Query(SqlUtil.GET_PRODUCTS_BY_SEARCH)
    Slice<ProductResultDto> getProductsBySearch(String search, Pageable pageable);

    @Query(SqlUtil.GET_ITEM)
    Optional<ProductResultDto> getItem(Long id);

    @Query(value = SqlUtil.GET_PRODUCT_CART, nativeQuery = true)
    List<ProductCartResultDto> getProductCart();
}
