package com.aipaas.anycloud.domain.provisioning.api.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
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
@Schema(description = "VM 클러스터 목록 항목 응답 DTO")
public class VmClusterListItemResponse {

    @Schema(description = "VM 클러스터 ID")
    private String id;

    @Schema(description = "클러스터 이름")
    private String clusterName;

    @Schema(description = "클라우드 제공자")
    private String clusterProvider;

    @Schema(description = "VM 클러스터 상태")
    private String status;

    @Schema(description = "VM 클러스터 상태 설명")
    private String statusDetail;

    @Schema(description = "현재 workflow 단계")
    private String currentWorkflowStep;

    @Schema(description = "마지막으로 완료된 단계 (영속화된 사실)")
    private String lastSuccessfulStep;

    @Schema(description = "마지막 실패 단계")
    private String lastFailedStep;

    @Schema(description = "workflow 재시도 횟수")
    private Integer workflowRetryCount;

    @Schema(description = "현재 step 의 시작 시각 (currentWorkflowStep 에 해당하는 timestamp)")
    private LocalDateTime stepStartedAt;

    @Schema(description = "BOOTSTRAP 단계 내부 sub-step. 예: BOOTSTRAP_MASTER_INIT")
    private String currentSubStep;

    @Schema(description = "현재 sub-step 시작 시각")
    private LocalDateTime subStepStartedAt;

    @Schema(description = "마지막 실패 분류 코드. ErrorResponse.code 와 동일 체계")
    private String lastErrorCode;

    @Schema(description = "환경")
    private String environment;

    @Schema(description = "리전")
    private String region;

    @Schema(description = "연결된 자격증명 이름")
    private String credentialName;

    @Schema(description = "자격증명 공급 방식")
    private String credentialSourceType;

    @Schema(description = "등록 완료 여부")
    private Boolean clusterRegistered;

    @Schema(description = "Master VM 스펙")
    private String masterVmSpec;

    @Schema(description = "Worker VM 스펙")
    private String workerVmSpec;

    @Schema(description = "선택된 OS 이미지")
    private String osImage;

    @Schema(description = "마지막 오류 메시지")
    private String lastError;

    @Schema(description = "생성 시각")
    private LocalDateTime createdAt;

    @Schema(description = "마지막 변경 시각")
    private LocalDateTime updatedAt;
}
