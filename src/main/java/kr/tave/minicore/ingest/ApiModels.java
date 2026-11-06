package kr.tave.minicore.ingest;

import java.util.List;

/**
 * 공공 API 응답을 문자열 그대로 담는 레코드들.
 * 필드명은 실제 응답 기준(2026-10-27 미리보기 샘플). 변환은 {@link Normalizer}가 담당한다.
 */
public final class ApiModels {

    private ApiModels() {
    }

    /** 응답 한 페이지. numOfRows/pageNo/totalCount는 응답에 없으면 null. */
    public record Page<T>(List<T> items, Integer numOfRows, Integer pageNo, Integer totalCount) {
    }

    /** 상품별 약관 정보조회 */
    public record TermsItem(
            String gdsNm,          // GDS_NM
            String aplcnBgngYmd,   // APLCN_BGNG_YMD
            String aplcnEndYmd,    // APLCN_END_YMD
            String stplUrlAddr     // STPL_URL_ADDR
    ) {
    }

    /** 1회 보험료계산 정보조회 */
    public record PremiumItem(
            String joinAge,        // JOIN_AGE_S4
            String sex,            // SEX_S1
            String prum,           // PRUM_S16              "33070원"
            String pymtTrm,        // PYMT_TRM_S10          "10년" / "전기납"
            String insuTrm,        // INSU_TRM_S10          "99" / "60" / "20"
            String unitAmt,        // PRUM_KEY_UNIT_AMT_S20 "1000만원"
            String ctrtDvsnNm,     // CTRT_DVSN_NM          "주계약"
            String ctrtTypNm,      // CTRT_TYP_NM_S50       "일반형"
            String gdsCd,          // GDS_CD_S10
            String gdsNm           // GDS_NM
    ) {
    }

    /** 상품별 보장내용 정보조회 (문장 1줄 = 1건) */
    public record CoverageLine(
            String insuNm,         // INSU_NM
            int lineNo,            // SNTC_LINE_NO
            String aplcnBgngYmd,   // APLCN_BGNG_YMD
            String aplcnEndYmd,    // APLCN_END_YMD
            String bnfiNm,         // GIVE_BNFI_NM
            String sntcCn          // GIVE_CN_SNTC_CN
    ) {
    }
}
