package kr.tave.minicore.ingest;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

final class Fixtures {

    static final Map<String, String> COVERAGE_NAME_MAP = new LinkedHashMap<>();

    static {
        COVERAGE_NAME_MAP.put("4008300", "무배당 우체국하나로OK보험(해약환급금50%지급형) 2609 1종(일반가입)(종신)");
        COVERAGE_NAME_MAP.put("4008400", "무배당 우체국하나로OK보험(표준형) 2609 1종(일반가입)(종신)");
        COVERAGE_NAME_MAP.put("4008500", "무배당 우체국하나로OK보험(해약환급금50%지급형) 2609 2종(간편가입)(종신)");
        COVERAGE_NAME_MAP.put("4008600", "무배당 우체국하나로OK보험(표준형) 2609 2종(간편가입)(종신)");
    }

    static final String EMPTY_RESPONSE =
            "{\"response\":{\"header\":{\"resultCode\":\"00\",\"resultMsg\":\"정상처리되었습니다\"},"
                    + "\"body\":{\"items\":{\"totalCount\":\"0\"}}}}";

    private Fixtures() {
    }

    static String read(String name) {
        try (InputStream in = Fixtures.class.getResourceAsStream("/fixtures/" + name)) {
            if (in == null) {
                throw new IllegalArgumentException("fixture 없음: " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
