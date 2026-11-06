package kr.tave.minicore.ingest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.tave.minicore.ingest.ApiModels.CoverageLine;
import kr.tave.minicore.ingest.ApiModels.Page;
import kr.tave.minicore.ingest.ApiModels.PremiumItem;
import kr.tave.minicore.ingest.ApiModels.TermsItem;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * 응답 구조: {"response":{"header":{"resultCode","resultMsg"},"body":{"items":{..., "item":[...]}}}}
 *
 * 방어 처리
 *  - XML 응답(형식 파라미터 누락, 인증키 오류 등) → 원문 일부를 담아 예외
 *  - resultCode != "00" → 예외
 *  - item이 배열이 아니라 객체 1개로 오거나, items가 비어 있는 경우 → 모두 리스트로 정규화
 */
public class ApiResponseParser {

    private final ObjectMapper mapper;

    public ApiResponseParser(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public Page<TermsItem> parseTerms(String body) {
        return parse(body, n -> new TermsItem(
                text(n, "GDS_NM"),
                text(n, "APLCN_BGNG_YMD"),
                text(n, "APLCN_END_YMD"),
                text(n, "STPL_URL_ADDR")));
    }

    public Page<PremiumItem> parsePremium(String body) {
        return parse(body, n -> new PremiumItem(
                text(n, "JOIN_AGE_S4"),
                text(n, "SEX_S1"),
                text(n, "PRUM_S16"),
                text(n, "PYMT_TRM_S10"),
                text(n, "INSU_TRM_S10"),
                text(n, "PRUM_KEY_UNIT_AMT_S20"),
                text(n, "CTRT_DVSN_NM"),
                text(n, "CTRT_TYP_NM_S50"),
                text(n, "GDS_CD_S10"),
                text(n, "GDS_NM")));
    }

    public Page<CoverageLine> parseCoverage(String body) {
        return parse(body, n -> new CoverageLine(
                text(n, "INSU_NM"),
                Integer.parseInt(text(n, "SNTC_LINE_NO")),  // 응답에서 숫자 타입으로 옴
                text(n, "APLCN_BGNG_YMD"),
                text(n, "APLCN_END_YMD"),
                text(n, "GIVE_BNFI_NM"),
                text(n, "GIVE_CN_SNTC_CN")));
    }

    private <T> Page<T> parse(String body, Function<JsonNode, T> itemMapper) {
        JsonNode root = readJson(body);
        JsonNode header = root.path("response").path("header");
        String code = header.path("resultCode").asText(null);
        if (code == null) {
            throw new ApiException("응답에 response.header.resultCode가 없음: " + preview(body));
        }
        if (!"00".equals(code)) {
            throw new ApiException("API 오류 resultCode=" + code + ", resultMsg=" + header.path("resultMsg").asText(""));
        }

        JsonNode items = root.path("response").path("body").path("items");
        List<T> result = new ArrayList<>();
        JsonNode item = items.path("item");
        if (item.isArray()) {
            item.forEach(n -> result.add(itemMapper.apply(n)));
        } else if (item.isObject()) {
            result.add(itemMapper.apply(item));
        }
        return new Page<>(result, intOrNull(items, "numOfRows"), intOrNull(items, "pageNo"), intOrNull(items, "totalCount"));
    }

    private JsonNode readJson(String body) {
        if (body == null || body.isBlank()) {
            throw new ApiException("빈 응답");
        }
        String trimmed = body.stripLeading();
        if (trimmed.startsWith("<")) {
            throw new ApiException("JSON이 아닌 XML 응답. 인증키(Decoding 키 사용 여부) 또는 "
                    + "JSON 형식 파라미터(ingest.common-params) 확인 필요: " + preview(body));
        }
        try {
            return mapper.readTree(trimmed);
        } catch (JsonProcessingException e) {
            throw new ApiException("JSON 파싱 실패: " + preview(body), e);
        }
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return (v == null || v.isNull()) ? "" : v.asText();
    }

    private static Integer intOrNull(JsonNode n, String field) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull() || v.asText().isBlank()) {
            return null;
        }
        return Integer.valueOf(v.asText().trim());
    }

    private static String preview(String body) {
        if (body == null) {
            return "null";
        }
        String oneLine = body.replaceAll("\\s+", " ");
        return oneLine.length() > 300 ? oneLine.substring(0, 300) + "…" : oneLine;
    }

    public static class ApiException extends RuntimeException {
        public ApiException(String message) {
            super(message);
        }

        public ApiException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
