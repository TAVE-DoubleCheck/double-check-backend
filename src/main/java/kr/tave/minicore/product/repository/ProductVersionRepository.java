package kr.tave.minicore.product.repository;

import kr.tave.minicore.product.domain.ProductVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface ProductVersionRepository extends JpaRepository<ProductVersion, Long> {

    /** 업무 날짜 기준 유효 버전. 기간 겹침은 DB EXCLUDE 제약이 막으므로 최대 1개다. */
    @Query("""
            select v from ProductVersion v
             where v.productCode = :productCode
               and v.effectiveFrom <= :today
               and (v.effectiveTo is null or v.effectiveTo >= :today)""")
    Optional<ProductVersion> findEffective(@Param("productCode") String productCode, @Param("today") LocalDate today);

    /** 목록 화면용: 오늘 유효한 모든 버전 (상품당 최대 1개) */
    @Query("""
            select v from ProductVersion v
             where v.effectiveFrom <= :today
               and (v.effectiveTo is null or v.effectiveTo >= :today)""")
    List<ProductVersion> findAllEffective(@Param("today") LocalDate today);
}
