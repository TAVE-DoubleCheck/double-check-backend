package kr.tave.minicore.ingest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 앱 기동 시 적재 (명세서: 최초 기동 시 1회 자동 실행).
 *  - product 테이블이 비어 있으면 실행
 *  - 이미 있으면 건너뜀. 강제 재실행: --ingest.force=true
 * 관리자 API(POST /api/admin/ingest)는 인증(BE C)이 붙은 뒤 추가한다.
 */
@Component
public class IngestRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(IngestRunner.class);

    private final IngestProperties props;
    private final IngestService service;
    private final IngestRepository repo;

    public IngestRunner(IngestProperties props, IngestService service, IngestRepository repo) {
        this.props = props;
        this.service = service;
        this.repo = repo;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!props.runOnStartup()) {
            return;
        }
        if (!props.force() && repo.count("product") > 0) {
            log.info("상품 데이터가 이미 있어 적재를 건너뜀. 다시 하려면 --ingest.force=true");
            return;
        }
        service.run();
    }
}
