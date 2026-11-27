package kr.tave.minicore.claim.api;

// 새 파일 (#35): 청구 거절 요청 (노션 명세 「청구 거절」). 사유는 목록 7개 중 하나, 메모는 선택.

import jakarta.validation.constraints.NotNull;
import kr.tave.minicore.claim.domain.RejectReason;

public record RejectRequest(
        @NotNull RejectReason reason, // 목록에 없는 값은 JSON 읽기 실패로 400
        String memo) {                // 자유 글
}
