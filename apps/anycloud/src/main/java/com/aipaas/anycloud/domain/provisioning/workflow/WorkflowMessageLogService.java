package com.aipaas.anycloud.domain.provisioning.workflow;

import com.aipaas.anycloud.domain.provisioning.api.response.FailedWorkflowMessageResponse;
import com.aipaas.anycloud.domain.provisioning.model.WorkflowMessageLogResult;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Workflow 메시지 처리 결과를 영속 기록한다.
 * Orchestrator / Guard 양쪽에서 사용한다.
 */
public interface WorkflowMessageLogService {

    /** PROCESSED — 정상 실행 후 종료. */
    void recordProcessed(VmClusterWorkflowMessage message, LocalDateTime startedAt);

    /** SKIPPED_* — 가드에 의해 처리되지 않음. */
    void recordSkipped(VmClusterWorkflowMessage message, WorkflowMessageLogResult skipReason);

    /** FAILED — 실행 중 예외. error 메시지 보존. */
    void recordFailed(VmClusterWorkflowMessage message, LocalDateTime startedAt, Throwable error);

    /**
     * FAILED 결과만 최근순으로 조회. cluster 별 필터 가능.
     *
     * @param clusterName null 이면 전체 클러스터의 FAILED row 반환
     * @param limit       페이지 크기 (1..200 권장)
     */
    List<FailedWorkflowMessageResponse> listFailed(String clusterName, int limit);

    /**
     * 지정 log row 의 step 을 새 messageId 로 재발행. PROVISION 은 ProvisioningRequest
     * 재구성이 필요해 unsupported — BOOTSTRAP / VERIFY / DESTROY 만 지원.
     *
     * @param logId workflow_message_log.id
     * @return 새로 발행된 messageId
     */
    String republish(String logId);
}
