package kr.tave.minicore.ingest;

import kr.tave.minicore.ingest.IngestPlanner.CoverageRow;
import kr.tave.minicore.ingest.IngestPlanner.ProductRow;
import kr.tave.minicore.ingest.IngestPlanner.RateRow;
import kr.tave.minicore.ingest.IngestPlanner.VersionRow;
import kr.tave.minicore.ingest.IngestPlanner.WritePlan;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * WritePlan을 한 트랜잭션으로 upsert한다. 중간에 실패하면 전부 롤백 → 반쯤 적재된 요율표가 남지 않는다.
 *
 * 기존 결정과 다른 점: 명세서는 Writer를 Spring Data JPA로 적었지만, upsert(ON CONFLICT)는
 * JPA가 직접 지원하지 않아 적재에만 JdbcTemplate을 쓴다. 도메인 엔티티(계약 등)는 JPA 그대로.
 *
 * 멱등성: 모든 INSERT가 자연키 기준 ON CONFLICT DO UPDATE. 값이 같으면 아무것도 바꾸지 않는다.
 * 삭제는 하지 않는다: API에서 사라진 요율은 남는다(이력 보존). 필요하면 별도 정책으로 처리.
 */
@Component
public class IngestWriter {

    public record WriteResult(int products, int versions, int rates, int coverages) {
    }

    private final JdbcTemplate jdbc;

    public IngestWriter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public WriteResult write(WritePlan plan) {
        for (ProductRow p : plan.products()) {
            jdbc.update("""
                    INSERT INTO product(product_code, short_name, full_name, terms_code)
                    VALUES (?, ?, ?, ?)
                    ON CONFLICT (product_code) DO UPDATE
                       SET short_name = EXCLUDED.short_name,
                           full_name  = EXCLUDED.full_name,
                           terms_code = EXCLUDED.terms_code,
                           updated_at = now()
                     WHERE (product.short_name, product.full_name, product.terms_code)
                           IS DISTINCT FROM (EXCLUDED.short_name, EXCLUDED.full_name, EXCLUDED.terms_code)""",
                    p.productCode(), p.shortName(), p.fullName(), p.termsCode());
        }

        // 버전은 (상품, 시작일) 오름차순으로 들어온다 → 기존 버전 종료일 갱신이 새 버전 삽입보다 먼저
        Map<String, Long> versionIds = new HashMap<>();
        for (VersionRow v : plan.versions()) {
            Long id = jdbc.queryForObject("""
                    INSERT INTO product_version(product_code, effective_from, effective_to, terms_url)
                    VALUES (?, ?, ?, ?)
                    ON CONFLICT (product_code, effective_from) DO UPDATE
                       SET effective_to = EXCLUDED.effective_to,
                           terms_url    = EXCLUDED.terms_url
                    RETURNING id""",
                    Long.class, v.productCode(), Date.valueOf(v.effectiveFrom()), sqlDate(v.effectiveTo()), v.termsUrl());
            versionIds.put(key(v.productCode(), v.effectiveFrom()), id);
        }

        List<Object[]> rateArgs = plan.rates().stream().map(r -> new Object[]{
                versionId(versionIds, r.productCode(), r.versionFrom()), r.joinAge(), r.sex(), r.payTermYears(),
                r.fullTermPay(), r.insureTermCode(), r.premiumWon(), r.unitAmountWon()
        }).toList();
        jdbc.batchUpdate("""
                INSERT INTO premium_rate(version_id, join_age, sex, pay_term_years, is_full_term_pay,
                                         insure_term_code, premium_won, unit_amount_won)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT ON CONSTRAINT pk_premium_rate DO UPDATE
                   SET is_full_term_pay = EXCLUDED.is_full_term_pay,
                       premium_won      = EXCLUDED.premium_won,
                       unit_amount_won  = EXCLUDED.unit_amount_won
                 WHERE (premium_rate.is_full_term_pay, premium_rate.premium_won, premium_rate.unit_amount_won)
                       IS DISTINCT FROM (EXCLUDED.is_full_term_pay, EXCLUDED.premium_won, EXCLUDED.unit_amount_won)""",
                rateArgs);

        List<Object[]> coverageArgs = plan.coverages().stream().map((CoverageRow c) -> new Object[]{
                versionId(versionIds, c.productCode(), c.versionFrom()), c.sourceLineNo(),
                c.benefitName(), c.text(), c.note()
        }).toList();
        jdbc.batchUpdate("""
                INSERT INTO coverage(version_id, source_line_no, benefit_name, condition_text, is_note)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT ON CONSTRAINT uq_coverage_paragraph DO UPDATE
                   SET benefit_name   = EXCLUDED.benefit_name,
                       condition_text = EXCLUDED.condition_text,
                       is_note        = EXCLUDED.is_note
                 WHERE (coverage.benefit_name, coverage.condition_text, coverage.is_note)
                       IS DISTINCT FROM (EXCLUDED.benefit_name, EXCLUDED.condition_text, EXCLUDED.is_note)""",
                coverageArgs);

        return new WriteResult(plan.products().size(), plan.versions().size(), rateArgs.size(), coverageArgs.size());
    }

    private static long versionId(Map<String, Long> ids, String code, LocalDate from) {
        Long id = ids.get(key(code, from));
        if (id == null) {
            throw new IllegalStateException("버전 id 없음: " + code + " " + from);
        }
        return id;
    }

    private static String key(String code, LocalDate from) {
        return code + "|" + from;
    }

    private static Date sqlDate(LocalDate d) {
        return d == null ? null : Date.valueOf(d);
    }
}
