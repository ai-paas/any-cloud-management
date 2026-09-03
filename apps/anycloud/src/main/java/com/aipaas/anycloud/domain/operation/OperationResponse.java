package com.aipaas.anycloud.domain.operation;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import lombok.Builder;

/**
 * Long-Running Operation 의 외부 노출 형태. 비밀/내부 식별자는 제외.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Operation (Long-Running) 표준 응답")
@Builder
public record OperationResponse(
        @Schema(description = "operation id", example = "op-7f3a8c2e1b4d") String id,
        @Schema(description = "type", example = "SCALE_CLUSTER") String type,
        @Schema(description = "대상 리소스 타입", example = "cluster") String resourceType,
        @Schema(description = "대상 리소스 식별자", example = "demo-aws-01") String resourceId,
        @Schema(description = "state", example = "RUNNING") String state,
        @Schema(description = "진행 정보") Progress progress,
        @Schema(description = "에러 메시지 (FAILED 시)") String errorMessage,
        @Schema(description = "시작 시각") LocalDateTime startedAt,
        @Schema(description = "종료 시각 (terminal 일 때)") LocalDateTime endedAt,
        @Schema(description = "생성 시각") LocalDateTime createdAt) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(description = "진행 단계")
    @Builder
    public record Progress(
            @Schema(description = "현재 단계명", example = "BOOTSTRAP") String currentStep,
            @Schema(description = "현재 단계 index (1-based)", example = "2") Integer stepIndex,
            @Schema(description = "총 단계 수", example = "3") Integer totalSteps,
            @Schema(description = "퍼센트 (0..100)", example = "66") Integer percent) {}

    public static OperationResponse from(OperationEntity e) {
        Progress p = (e.getCurrentStep() != null
                        || e.getStepIndex() != null
                        || e.getTotalSteps() != null
                        || e.getPercent() != null)
                ? Progress.builder()
                        .currentStep(e.getCurrentStep())
                        .stepIndex(e.getStepIndex())
                        .totalSteps(e.getTotalSteps())
                        .percent(e.getPercent())
                        .build()
                : null;
        return OperationResponse.builder()
                .id(e.getId())
                .type(e.getType() == null ? null : e.getType().name())
                .resourceType(e.getResourceType())
                .resourceId(e.getResourceId())
                .state(e.getState() == null ? null : e.getState().name())
                .progress(p)
                .errorMessage(e.getErrorMessage())
                .startedAt(e.getStartedAt())
                .endedAt(e.getEndedAt())
                .createdAt(e.getCreatedAt())
                .build();
    }

    /** domain record 입력의 overload. */
    public static OperationResponse from(com.aipaas.anycloud.domain.operation.Operation d) {
        Progress p = (d.currentStep() != null || d.stepIndex() != null || d.totalSteps() != null || d.percent() != null)
                ? Progress.builder()
                        .currentStep(d.currentStep())
                        .stepIndex(d.stepIndex())
                        .totalSteps(d.totalSteps())
                        .percent(d.percent())
                        .build()
                : null;
        return OperationResponse.builder()
                .id(d.id())
                .type(d.type() == null ? null : d.type().name())
                .resourceType(d.resourceType())
                .resourceId(d.resourceId())
                .state(d.state() == null ? null : d.state().name())
                .progress(p)
                .errorMessage(d.errorMessage())
                .startedAt(d.startedAt())
                .endedAt(d.endedAt())
                .createdAt(d.createdAt())
                .build();
    }
}
