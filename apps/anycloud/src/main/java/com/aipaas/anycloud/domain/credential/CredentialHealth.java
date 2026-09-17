package com.aipaas.anycloud.domain.credential;

import io.swagger.v3.oas.annotations.media.Schema;

/** 자격증명 확인 결과. */
@Schema(description = "자격증명 가용성 확인 결과")
public record CredentialHealth(
        @Schema(description = "CSP API 호출이 성공했는지", example = "true") boolean healthy,
        @Schema(description = "실패 원인 분류", example = "PERMISSION_DENIED") String kind,
        @Schema(description = "사용자가 할 일") String hint,
        @Schema(description = "CSP 원본 메시지 — 지원 문의용") String detail,
        @Schema(description = "확인 과정에서 조회된 리전 수", example = "41") int checkedRegions,
        @Schema(description = "확인 시각. null 이면 한 번도 확인한 적이 없다") java.time.LocalDateTime checkedAt) {}
