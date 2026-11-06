package kr.tave.minicore.product.repository;

import kr.tave.minicore.product.domain.PremiumRate;
import kr.tave.minicore.product.domain.PremiumRateId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface PremiumRateRepository extends JpaRepository<PremiumRate, PremiumRateId> {

    /** 납입 플랜 유도용 튜플. 플랜 묶음 규칙은 PayTermPlanAssembler 참고 */
    @Query("""
            select distinct r.id.sex as sex, r.id.joinAge as joinAge, r.id.payTermYears as payTermYears
              from PremiumRate r
             where r.id.versionId = :versionId""")
    List<PayAgeView> findPayAgeTuples(@Param("versionId") long versionId);

    /** 버전의 보험기간 코드 DISTINCT ('99'=종신, 그 외 = 만기까지 남은 연수) */
    @Query("select distinct r.id.insureTermCode from PremiumRate r where r.id.versionId = :versionId")
    List<String> findDistinctInsureTermCodes(@Param("versionId") long versionId);

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
