package kr.tave.minicore.product.repository;

import kr.tave.minicore.product.domain.PremiumRate;
import kr.tave.minicore.product.domain.PremiumRateId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface PremiumRateRepository extends JpaRepository<PremiumRate, PremiumRateId> {

    /** 버전의 선택 가능 조합 (납입기간·전기납 여부·보험기간 코드 DISTINCT) */
    @Query("""
            select distinct new kr.tave.minicore.product.repository.RateOption(
                       r.id.payTermYears, r.fullTermPay, r.id.insureTermCode)
              from PremiumRate r
             where r.id.versionId = :versionId
             order by r.id.payTermYears, r.id.insureTermCode""")
    List<RateOption> findOptions(@Param("versionId") long versionId);

    /** 버전·성별의 가입나이 범위 (요율표 존재 여부로 가입 가능 나이를 판단한다 — 약관에 명시 없음) */
    @Query("""
            select min(r.id.joinAge) as min, max(r.id.joinAge) as max
              from PremiumRate r
             where r.id.versionId = :versionId and r.id.sex = :sex""")
    AgeRangeView findJoinAgeRange(@Param("versionId") long versionId, @Param("sex") Character sex);

    /** 버전의 기준단위금액 DISTINCT (현재 데이터는 1,000만 원 1종류여야 한다) */
    @Query("select distinct r.unitAmountWon from PremiumRate r where r.id.versionId = :versionId")
    List<Long> findDistinctUnitAmounts(@Param("versionId") long versionId);

    /** 종신 여부 판정용: 99가 아닌 보험기간 코드가 하나라도 있으면 TERM 상품 */
    boolean existsById_VersionIdAndId_InsureTermCodeNot(long versionId, String insureTermCode);
}
