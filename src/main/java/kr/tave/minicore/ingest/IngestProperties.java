package kr.tave.minicore.ingest;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.Map;

/** application.yml의 ingest.* 설정 */
@ConfigurationProperties("ingest")
public record IngestProperties(
        @DefaultValue("true") boolean runOnStartup,
        @DefaultValue("false") boolean force,
        String serviceKey,
        @DefaultValue("100") long callIntervalMs,
        Map<String, String> commonParams,
        Terms terms,
        Premium premium,
        Coverage coverage,
        Map<String, String> coverageNameMap
) {
    public IngestProperties {
        commonParams = commonParams == null ? Map.of() : commonParams;
        coverageNameMap = coverageNameMap == null ? Map.of() : coverageNameMap;
    }

    public record Terms(String url, Map<String, String> params) {
        public Terms {
            params = params == null ? Map.of() : params;
        }
    }

    public record Premium(String url, String productNameQuery,
                          @DefaultValue("0") int ageMin, @DefaultValue("80") int ageMax) {
    }

    public record Coverage(String url, Map<String, String> params,
                           @DefaultValue("100") int pageSize, @DefaultValue("50") int maxPages) {
        public Coverage {
            params = params == null ? Map.of() : params;
        }
    }
}
