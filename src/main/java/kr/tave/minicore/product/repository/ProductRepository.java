package kr.tave.minicore.product.repository;

import kr.tave.minicore.product.domain.Product;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProductRepository extends JpaRepository<Product, String> {

    List<Product> findAllByOrderByProductCode();
}
