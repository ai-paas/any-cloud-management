package com.aipaas.anycloud.domain.provisioning.web;

import com.aipaas.anycloud.common.web.ActionResponse;
import com.aipaas.anycloud.common.web.ApiSuccessResponse;
import com.aipaas.anycloud.common.web.PagedData;
import com.aipaas.anycloud.domain.provisioning.api.response.FailedWorkflowMessageResponse;
import com.aipaas.anycloud.domain.provisioning.api.response.VmClusterWorkflowQueueResponse;
import com.aipaas.anycloud.domain.provisioning.workflow.VmClusterWorkflowQueueService;
import com.aipaas.anycloud.domain.provisioning.workflow.WorkflowMessageLogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Workflow controller — RabbitMQ + workflow 활성 환경에서만 실제 동작.
 *
 * <p>Service bean 들을 {@link ObjectProvider} 로 받아 nullable 주입. RabbitMQ 미설정 / 접속 실패
 * 환경에서도 부팅은 통과하고, 본 endpoint 호출 시점에만 503 Service Unavailable 반환.
 */
@RestController
@RequestMapping("/v1/workflow")
@Tag(name = "Workflow (v1)", description = "VM Cluster Workflow 관리 (queues, dead-letter-messages)")
public class VmClusterWorkflowController {

    private final ObjectProvider<VmClusterWorkflowQueueService> queueServiceProvider;
    private final ObjectProvider<WorkflowMessageLogService> logServiceProvider;

    public VmClusterWorkflowController(
            ObjectProvider<VmClusterWorkflowQueueService> queueServiceProvider,
            ObjectProvider<WorkflowMessageLogService> logServiceProvider) {
        this.queueServiceProvider = queueServiceProvider;
        this.logServiceProvider = logServiceProvider;
    }

    /** Service bean 없으면 503 — RabbitMQ 미설정 환경에서 endpoint 호출 시 graceful fail. */
    private VmClusterWorkflowQueueService queueService() {
        VmClusterWorkflowQueueService s = queueServiceProvider.getIfAvailable();
        if (s == null) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "Workflow service unavailable — RabbitMQ 미연결 또는 workflow 비활성.");
        }
        return s;
    }

    private WorkflowMessageLogService logService() {
        WorkflowMessageLogService s = logServiceProvider.getIfAvailable();
        if (s == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Workflow log service unavailable.");
        }
        return s;
    }

    @GetMapping("/queues")
    @Operation(summary = "VM workflow queue 상태 조회", description = "RabbitMQ 기반 VM workflow queue와 DLQ 상태를 조회합니다.")
    public ResponseEntity<ApiSuccessResponse<PagedData<VmClusterWorkflowQueueResponse>>> getWorkflowQueues() {
        return new ResponseEntity<>(
                ApiSuccessResponse.of(
                        HttpStatus.OK.value(),
                        "VM workflow queues loaded",
                        PagedData.of(queueService().getWorkflowQueues())),
                new HttpHeaders(),
                HttpStatus.OK);
    }

    @GetMapping("/dead-letter-messages")
    @Operation(
            summary = "워크플로우 FAILED 처리 이력 조회",
            description =
                    "workflow_message_log 의 FAILED row 를 최근순으로 반환합니다. " + "운영자가 step / 사유 / 소요시간을 확인 후 재발행 결정에 사용합니다.")
    public ResponseEntity<ApiSuccessResponse<PagedData<FailedWorkflowMessageResponse>>> listFailedMessages(
            @Parameter(description = "클러스터 이름 필터(생략 시 전체)", example = "demo-aws-01")
                    @RequestParam(value = "clusterName", required = false)
                    String clusterName,
            @Parameter(description = "최대 row 수 (1..200)", example = "20")
                    @RequestParam(value = "limit", defaultValue = "20")
                    int limit) {
        List<FailedWorkflowMessageResponse> result = logService().listFailed(clusterName, limit);
        return new ResponseEntity<>(
                ApiSuccessResponse.of(HttpStatus.OK.value(), "Failed workflow messages loaded", PagedData.of(result)),
                new HttpHeaders(),
                HttpStatus.OK);
    }

    @PostMapping("/dead-letter-messages/{messageId}/operations")
    @Operation(
            summary = "DLQ 메시지 액션 operation",
            description = "지정 log row 의 step 을 새 messageId 로 publish (replay). "
                    + "BOOTSTRAP / VERIFY / DESTROY 만 지원하며 PROVISION 은 /v1/clusters/{name}/operations "
                    + "(type=retryWorkflow) 또는 DELETE 후 재생성을 사용합니다.")
    public ResponseEntity<ApiSuccessResponse<ActionResponse>> replayMessage(
            @Parameter(description = "workflow_message_log.id", example = "uuid") @PathVariable String messageId) {
        String logId = messageId;
        String newMessageId = logService().republish(logId);
        return new ResponseEntity<>(
                ApiSuccessResponse.of(
                        HttpStatus.ACCEPTED.value(),
                        "Workflow message republished",
                        ActionResponse.builder()
                                .resourceType("workflowMessage")
                                .resourceId(logId)
                                .operation("republish")
                                .state(newMessageId)
                                .build()),
                new HttpHeaders(),
                HttpStatus.ACCEPTED);
    }
}
