package com.aipaas.anycloud.domain.provisioning.api.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.ZonedDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "VM 클러스터 구성 요소 상태 — 백엔드가 SSH 로 직접 설치하는 계층")
public class VmClusterComponentResponse {

    @Schema(description = "구성 요소", example = "AGENT")
    private String type;

    @Schema(description = "READY 판정 반영 여부", example = "REQUIRED")
    private String requirement;

    @Schema(description = "관측 결과", example = "NOT_READY")
    private String health;

    @Schema(description = "누적 재적용 시도 횟수", example = "4")
    private Integer attempts;

    @Schema(description = "다음 재적용 예정 시각")
    private ZonedDateTime nextAttemptAt;

    @Schema(description = "마지막 관측 시각")
    private ZonedDateTime lastProbedAt;

    @Schema(description = "미충족 사유", example = "agent 미연결 (cluster status=AGENT_PENDING)")
    private String lastError;
}
