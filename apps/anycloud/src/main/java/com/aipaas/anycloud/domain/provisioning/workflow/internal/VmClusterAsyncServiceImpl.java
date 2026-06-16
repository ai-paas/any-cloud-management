package com.aipaas.anycloud.domain.provisioning.workflow.internal;

import com.aipaas.anycloud.common.error.exception.ClusterNotFoundException;
import com.aipaas.anycloud.common.util.AfterCommitPublisher;
import com.aipaas.anycloud.domain.provisioning.VmClusterRepository;
import com.aipaas.anycloud.domain.provisioning.workflow.VmClusterAsyncService;
import com.aipaas.anycloud.domain.provisioning.workflow.VmClusterWorkflowMessage;
import com.aipaas.anycloud.domain.provisioning.workflow.VmClusterWorkflowPublisher;
import com.aipaas.anycloud.domain.provisioning.workflow.VmClusterWorkflowStep;
import io.aipaas.cluster.provisioning.core.ProvisioningRequest;
import java.util.concurrent.CompletableFuture;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * VM cluster async workflow facade — caller (Service/Controller) 의 단일 진입점.
 *
 * <p>본 클래스는 routing 만 담당:
 * <ul>
 *   <li><b>provision / destroy</b>: RabbitMQ 메시지 publish (afterCommit) — 실제 step 은 listener
 *       (RabbitMqVmClusterOrchestratorListener / RabbitMqVmClusterWorkerListener) 에서 실행.</li>
 *   <li><b>scale</b>: 동기 step service ({@link VmClusterScaleStepService}) 에 위임 — 본 facade 는
 *       입력 검증만 ({@link ClusterNotFoundException}). step service 가 자체 {@code @Async} 로 별도
 *       thread 에서 실행.</li>
 * </ul>
 *
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VmClusterAsyncServiceImpl implements VmClusterAsyncService {

    private final VmClusterRepository vmClusterRepository;
    private final VmClusterWorkflowPublisher vmClusterWorkflowPublisher;
    private final AfterCommitPublisher afterCommitPublisher;
    private final VmClusterScaleStepService scaleStepService;

    @Override
    public CompletableFuture<Void> provisionClusterAsync(
            String provisioningId, String clusterName, ProvisioningRequest request) {
        vmClusterRepository.findById(provisioningId).orElseThrow(() -> new ClusterNotFoundException(clusterName));
        // caller TX 가 commit 된 뒤에만 RabbitMQ 로 발행. rollback 시 message 도 가지 않음.
        // TX 컨텍스트 없을 땐 즉시 발행 (fallback).
        var msg = VmClusterWorkflowMessage.builder()
                .vmClusterId(provisioningId)
                .clusterName(clusterName)
                .step(VmClusterWorkflowStep.PROVISION)
                .provisioningRequest(request)
                .build();
        afterCommitPublisher.publish(
                "vmCluster.publishProvision", () -> vmClusterWorkflowPublisher.publishProvision(msg));
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> destroyClusterAsync(String clusterName) {
        var provisioning = vmClusterRepository
                .findFirstByClusterNameOrderByCreatedAtDesc(clusterName)
                .orElseThrow(() -> new ClusterNotFoundException(clusterName));
        var destroyMsg = VmClusterWorkflowMessage.builder()
                .vmClusterId(provisioning.getId())
                .clusterName(clusterName)
                .stackName(provisioning.getStackName())
                .step(VmClusterWorkflowStep.DESTROY)
                .build();
        afterCommitPublisher.publish(
                "vmCluster.publishDestroy", () -> vmClusterWorkflowPublisher.publishDestroy(destroyMsg));
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> scaleClusterAsync(String clusterName, int workerCount) {
        return scaleStepService.scaleClusterAsync(clusterName, workerCount);
    }
}
