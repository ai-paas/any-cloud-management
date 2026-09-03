package com.aipaas.anycloud.domain.operation;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Long-running workflow step 의 진행률 / 완료 / 실패 마킹을 위한 best-effort helper.
 *
 * <p>{@link OperationService#findLatestActiveByResource} 로 active operation 을 찾아 progress 를
 * push. Operation 자원이 없거나 이미 terminal 상태면 silent skip — 호출 측이 별도 try/catch 할 필요
 * 없도록 본 helper 가 모든 예외를 흡수 (logging 만).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OperationProgressTracker {

    private final OperationService operationService;

    /** mid-step progress push. */
    public void updateProgress(String resourceType, String resourceId, String stepLabel, int stepIndex, int percent) {
        try {
            operationService
                    .findLatestActiveByResource(resourceType, resourceId)
                    .ifPresent(op -> operationService.updateProgress(op.getId(), stepLabel, stepIndex, percent));
        } catch (Exception ex) {
            log.warn(
                    "Operation progress push skipped ({}/{}, step {}): {}",
                    resourceType,
                    resourceId,
                    stepLabel,
                    ex.toString());
        }
    }

    /** Active operation 을 SUCCEEDED 로 마킹. resourceId 가 없거나 active op 없으면 silent. */
    public void complete(String resourceType, String resourceId, String resultJson) {
        try {
            operationService
                    .findLatestActiveByResource(resourceType, resourceId)
                    .ifPresent(op -> operationService.complete(op.getId(), resultJson));
        } catch (Exception ex) {
            log.warn("Operation complete skipped ({}/{}): {}", resourceType, resourceId, ex.toString());
        }
    }

    /** Active operation 을 FAILED 로 마킹. */
    public void fail(String resourceType, String resourceId, String errorMessage) {
        try {
            operationService
                    .findLatestActiveByResource(resourceType, resourceId)
                    .ifPresent(op -> operationService.fail(op.getId(), errorMessage));
        } catch (Exception ex) {
            log.warn("Operation fail mark skipped ({}/{}): {}", resourceType, resourceId, ex.toString());
        }
    }
}
