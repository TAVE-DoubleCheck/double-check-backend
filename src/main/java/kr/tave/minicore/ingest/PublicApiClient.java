package kr.tave.minicore.ingest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 공공데이터포털 GET 호출. 응답 본문을 문자열 그대로 돌려준다(파싱은 ApiResponseParser).
 *
 * 인증키: Decoding 키를 받아 여기서 한 번만 URL 인코딩한다.
 *  - 쿼리 값은 URI 변수로 넣고 encode()하므로 '+', '/', '=' 까지 인코딩된다.
 *  - Encoding 키를 넣으면 이중 인코딩되어 SERVICE_KEY_IS_NOT_REGISTERED_ERROR가 난다.
 * 로그에는 인증키를 남기지 않는다.
 */
@Component
public class PublicApiClient {

    private static final Logger log = LoggerFactory.getLogger(PublicApiClient.class);

    private final RestClient restClient;

    public PublicApiClient() {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(30));
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    public String get(String baseUrl, String serviceKey, Map<String, String> params) {
        Map<String, String> all = new LinkedHashMap<>();
        all.put("serviceKey", serviceKey);
        all.putAll(params);

        UriComponentsBuilder b = UriComponentsBuilder.fromUriString(baseUrl);
        List<Object> values = new ArrayList<>();
        int i = 0;
        for (Map.Entry<String, String> e : all.entrySet()) {
            b.queryParam(e.getKey(), "{v" + i++ + "}");
            values.add(e.getValue());
        }
        URI uri = b.encode().buildAndExpand(values.toArray()).toUri();
        log.debug("GET {} {}", baseUrl, params);  // 인증키 제외

        // Content-Type에 charset이 없으면 한글이 깨질 수 있어 바이트로 받아 UTF-8로 해석
        byte[] body = restClient.get().uri(uri).retrieve().body(byte[].class);
        return body == null ? "" : new String(body, StandardCharsets.UTF_8);
    }
}
