package com.aipaas.anycloud.domain.provisioning.api.response;

import com.aipaas.anycloud.domain.provisioning.WorkflowMessageLogEntity;
import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.LocalDateTime;
import lombok.Builder;
import lombok.Getter;

/** workflow_message_log 의 FAILED row 를 운영자에게 노출하는 DTO. */
@Getter
@Builder
public class FailedWorkflowMessageResponse {

    private final String logId;
    private final String messageId;
    private final String vmClusterId;
    private final String clusterName;
    private final String step;
    private final Long durationMs;
    private final String errorMessage;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private final LocalDateTime startedAt;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private final LocalDateTime completedAt;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private final LocalDateTime createdAt;

    public static FailedWorkflowMessageResponse from(WorkflowMessageLogEntity entity) {
        return FailedWorkflowMessageResponse.builder()
                .logId(entity.getId())
                .messageId(entity.getMessageId())
                .vmClusterId(entity.getVmClusterId())
                .clusterName(entity.getClusterName())
                .step(entity.getStep() == null ? null : entity.getStep().name())
                .durationMs(entity.getDurationMs())
                .errorMessage(entity.getErrorMessage())
                .startedAt(entity.getStartedAt())
                .completedAt(entity.getCompletedAt())
                .createdAt(entity.getCreatedAt())
                .build();
    }
}
