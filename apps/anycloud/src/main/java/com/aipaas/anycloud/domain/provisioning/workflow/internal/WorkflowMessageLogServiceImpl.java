package com.aipaas.anycloud.domain.provisioning.workflow.internal;

import com.aipaas.anycloud.common.error.enums.ErrorCode;
import com.aipaas.anycloud.common.error.exception.CustomException;
import com.aipaas.anycloud.domain.provisioning.WorkflowMessageLogEntity;
import com.aipaas.anycloud.domain.provisioning.WorkflowMessageLogRepository;
import com.aipaas.anycloud.domain.provisioning.api.response.FailedWorkflowMessageResponse;
import com.aipaas.anycloud.domain.provisioning.model.WorkflowMessageLogResult;
import com.aipaas.anycloud.domain.provisioning.workflow.VmClusterWorkflowMessage;
import com.aipaas.anycloud.domain.provisioning.workflow.VmClusterWorkflowPublisher;
import com.aipaas.anycloud.domain.provisioning.workflow.VmClusterWorkflowStep;
import com.aipaas.anycloud.domain.provisioning.workflow.WorkflowMessageLogService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowMessageLogServiceImpl implements WorkflowMessageLogService {

    private static final int MAX_ERROR_MESSAGE_LEN = 8000;
    private static final String METRIC_COUNT = "anycloud.workflow.messages";
    private static final String METRIC_DURATION = "anycloud.workflow.step.duration";

    private final WorkflowMessageLogRepository repository;
    private final MeterRegistry meterRegistry;
    private final VmClusterWorkflowPublisher workflowPublisher;

    @Override
    public void recordProcessed(VmClusterWorkflowMessage message, LocalDateTime startedAt) {
        if (!isLoggable(message)) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        WorkflowMessageLogEntity entity = baseEntity(message, startedAt, WorkflowMessageLogResult.PROCESSED);
        entity.setCompletedAt(now);
        entity.setDurationMs(durationMs(startedAt, now));
        save(entity);
    }

    @Override
    public void recordSkipped(VmClusterWorkflowMessage message, WorkflowMessageLogResult skipReason) {
        if (!isLoggable(message)) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        WorkflowMessageLogEntity entity = baseEntity(message, now, skipReason);
        entity.setCompletedAt(now);
        entity.setDurationMs(0L);
        save(entity);
    }

    @Override
    public void recordFailed(VmClusterWorkflowMessage message, LocalDateTime startedAt, Throwable error) {
        if (!isLoggable(message)) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        WorkflowMessageLogEntity entity = baseEntity(message, startedAt, WorkflowMessageLogResult.FAILED);
        entity.setCompletedAt(now);
        entity.setDurationMs(durationMs(startedAt, now));
        entity.setErrorMessage(truncate(stackTraceAsString(error)));
        save(entity);
    }

    private WorkflowMessageLogEntity baseEntity(
            VmClusterWorkflowMessage message, LocalDateTime startedAt, WorkflowMessageLogResult result) {
        return WorkflowMessageLogEntity.builder()
                .messageId(message.getMessageId())
                .vmClusterId(message.getVmClusterId())
                .clusterName(message.getClusterName())
                .step(message.getStep())
                .result(result)
                .startedAt(startedAt)
                .build();
    }

    private void save(WorkflowMessageLogEntity entity) {
        try {
            repository.save(entity);
        } catch (RuntimeException e) {
            // 로그 저장 실패가 본 작업을 막아서는 안 된다.
            log.warn("Failed to persist workflow message log ({}): {}", entity.getResult(), e.toString());
        }
        // Prometheus metric — DB 저장 성공/실패와 무관하게 in-memory counter / timer 갱신.
        // /actuator/prometheus 로 노출되어 Grafana / Alertmanager 가 가져간다.
        Tags tags = Tags.of(
                "step", entity.getStep() != null ? entity.getStep().name() : "UNKNOWN",
                "result", entity.getResult() != null ? entity.getResult().name() : "UNKNOWN");
        meterRegistry.counter(METRIC_COUNT, tags).increment();
        if (entity.getDurationMs() != null) {
            Timer.builder(METRIC_DURATION)
                    .tags(tags)
                    .publishPercentiles(0.5, 0.95, 0.99)
                    .register(meterRegistry)
                    .record(entity.getDurationMs(), TimeUnit.MILLISECONDS);
        }
    }

    private boolean isLoggable(VmClusterWorkflowMessage message) {
        return message != null
                && message.getMessageId() != null
                && !message.getMessageId().isBlank()
                && message.getStep() != null;
    }

    private long durationMs(LocalDateTime start, LocalDateTime end) {
        return Duration.between(start, end).toMillis();
    }

    private String stackTraceAsString(Throwable t) {
        if (t == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(t.toString()).append('\n');
        for (StackTraceElement el : t.getStackTrace()) {
            sb.append("\tat ").append(el).append('\n');
        }
        return sb.toString();
    }

    private String truncate(String text) {
        if (text == null) {
            return null;
        }
        return text.length() <= MAX_ERROR_MESSAGE_LEN ? text : text.substring(0, MAX_ERROR_MESSAGE_LEN);
    }

    @Override
    public List<FailedWorkflowMessageResponse> listFailed(String clusterName, int limit) {
        int capped = Math.min(Math.max(limit, 1), 200);
        String cluster = (clusterName == null || clusterName.isBlank()) ? null : clusterName;
        return repository.findFailed(cluster, PageRequest.of(0, capped)).stream()
                .map(FailedWorkflowMessageResponse::from)
                .toList();
    }

    @Override
    public String republish(String logId) {
        WorkflowMessageLogEntity entry = repository
                .findById(logId)
                .orElseThrow(() -> new CustomException("Workflow log not found: " + logId, ErrorCode.ENTITY_NOT_FOUND));

        VmClusterWorkflowStep step = entry.getStep();
        if (step == null) {
            throw new CustomException("Workflow log has null step: " + logId, ErrorCode.INVALID_INPUT_VALUE);
        }
        if (step == VmClusterWorkflowStep.PROVISION) {
            throw new CustomException(
                    "PROVISION 재발행은 ProvisioningRequest 재구성이 필요하므로 지원하지 않습니다. "
                            + "POST /vm/clusters/{name}/retry 또는 DELETE 후 재생성을 사용하세요.",
                    ErrorCode.INVALID_INPUT_VALUE);
        }

        String newMessageId = UUID.randomUUID().toString();
        VmClusterWorkflowMessage message = VmClusterWorkflowMessage.builder()
                .messageId(newMessageId)
                .vmClusterId(entry.getVmClusterId())
                .clusterName(entry.getClusterName())
                .step(step)
                .build();

        switch (step) {
            case BOOTSTRAP -> workflowPublisher.publishBootstrap(message);
            case VERIFY -> workflowPublisher.publishVerify(message);
            case DESTROY -> workflowPublisher.publishDestroy(message);
            default -> throw new CustomException("Unsupported step: " + step, ErrorCode.INVALID_INPUT_VALUE);
        }
        log.info(
                "Republished workflow message {} for cluster {} step {} (from log {})",
                newMessageId,
                entry.getClusterName(),
                step,
                logId);
        return newMessageId;
    }
}
