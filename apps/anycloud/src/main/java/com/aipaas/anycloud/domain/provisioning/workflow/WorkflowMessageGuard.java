package com.aipaas.anycloud.domain.provisioning.workflow;

import com.aipaas.anycloud.domain.provisioning.VmClusterEntity;
import com.aipaas.anycloud.domain.provisioning.VmClusterRepository;
import com.aipaas.anycloud.domain.provisioning.model.WorkflowMessageLogResult;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * RabbitMQ 의 at-least-once 전달 보장으로 인해 동일 워크플로우 메시지가 두 번 도착할 수 있다.
 * 본 가드는 메시지 처리 직전과 직후를 감싸 다음을 보장한다.
 *
 * <ol>
 *   <li><b>중복 차단</b>: vm_cluster.last_processed_workflow_message_id 와 동일하면 no-op</li>
 *   <li><b>단계 순서 가드</b>: 현재 클러스터 상태에 비춰 이미 지나간 단계 메시지가 오면 no-op</li>
 *   <li><b>처리 완료 기록</b>: {@link #markProcessed} 호출 시 messageId 를 영속화</li>
 * </ol>
 *
 * 모든 판단은 vmClusterId 기반. vmClusterId 가 없는 메시지(예: 일부 destroy 흐름)는
 * 가드를 통과시켜 호출부의 별도 처리에 위임한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkflowMessageGuard {

    private final VmClusterRepository vmClusterRepository;
    private final WorkflowMessageLogService workflowMessageLogService;

    /**
     * 메시지를 처리해야 하는지 여부를 반환한다. true=처리, false=스킵.
     */
    public boolean shouldProcess(VmClusterWorkflowMessage message) {
        if (message == null) {
            return false;
        }
        String vmClusterId = message.getVmClusterId();
        if (vmClusterId == null || vmClusterId.isBlank()) {
            // 가드 키 없음 — 호출부 책임. 통과.
            return true;
        }

        Optional<VmClusterEntity> opt = vmClusterRepository.findById(vmClusterId);
        if (opt.isEmpty()) {
            log.warn(
                    "Workflow message references unknown vmClusterId={} (messageId={}, step={}); skipping",
                    vmClusterId,
                    message.getMessageId(),
                    message.getStep());
            workflowMessageLogService.recordSkipped(message, WorkflowMessageLogResult.SKIPPED_NOT_FOUND);
            return false;
        }
        VmClusterEntity entity = opt.get();

        //  세대(generation) 가드 — 같은 clusterName 의 최신 row 가 아니면 옛 세대를
        // 가리키는 잔존 메시지다. Backend 재시작 시 unacked 메시지가 재전달되면서 이미
        // 삭제/대체된 cluster 의 옛 row 를 부활시켜 pulumi up 까지 실행하는 사고의 직접 원인
        // (dev 에서 DELETED demo-aws-01 부활로 실증). status staleness 검사는 해당 row 자신의
        // 상태만 보므로 이 케이스를 못 잡는다.
        boolean superseded = vmClusterRepository
                .findFirstByClusterNameOrderByCreatedAtDesc(entity.getClusterName())
                .map(latest -> !Objects.equals(latest.getId(), entity.getId()))
                .orElse(false);
        if (superseded || entity.getDeletedAt() != null) {
            log.warn(
                    "Workflow message targets superseded/deleted vm_cluster row (vmClusterId={}, cluster={}, "
                            + "messageId={}, step={}, status={}); skipping",
                    vmClusterId,
                    entity.getClusterName(),
                    message.getMessageId(),
                    message.getStep(),
                    entity.getProvisioningStatus());
            workflowMessageLogService.recordSkipped(message, WorkflowMessageLogResult.SKIPPED_STALE);
            return false;
        }

        String messageId = message.getMessageId();
        if (messageId != null && Objects.equals(messageId, entity.getLastProcessedWorkflowMessageId())) {
            log.info(
                    "Duplicate workflow message detected (vmClusterId={}, messageId={}, step={}); skipping",
                    vmClusterId,
                    messageId,
                    message.getStep());
            workflowMessageLogService.recordSkipped(message, WorkflowMessageLogResult.SKIPPED_DUPLICATE);
            return false;
        }

        if (isStaleForCurrentStatus(message.getStep(), entity)) {
            log.info(
                    "Stale workflow step (vmClusterId={}, messageId={}, step={}, currentStatus={}, currentStep={}); skipping",
                    vmClusterId,
                    messageId,
                    message.getStep(),
                    entity.getProvisioningStatus(),
                    entity.getCurrentWorkflowStep());
            workflowMessageLogService.recordSkipped(message, WorkflowMessageLogResult.SKIPPED_STALE);
            return false;
        }

        return true;
    }

    /**
     * 메시지 처리 완료 후 messageId 를 영속화한다. 같은 메시지가 다시 와도 다음 호출은 {@link #shouldProcess} 에서 스킵된다.
     */
    public void markProcessed(VmClusterWorkflowMessage message) {
        if (message == null) {
            return;
        }
        String vmClusterId = message.getVmClusterId();
        String messageId = message.getMessageId();
        if (vmClusterId == null || vmClusterId.isBlank() || messageId == null || messageId.isBlank()) {
            return;
        }
        vmClusterRepository.findById(vmClusterId).ifPresent(entity -> {
            entity.setLastProcessedWorkflowMessageId(messageId);
            vmClusterRepository.save(entity);
        });
    }

    /**
     * step 별로 entity 상태가 이미 그 단계를 지나간 경우를 감지한다.
     * 단계 도착 순서가 비정상일 때(예: VERIFY 완료 후 PROVISION 재도착) 메시지를 스킵하기 위함.
     */
    private boolean isStaleForCurrentStatus(VmClusterWorkflowStep step, VmClusterEntity entity) {
        return step != null && step.isStaleForStatus(entity.getProvisioningStatus());
    }
}
