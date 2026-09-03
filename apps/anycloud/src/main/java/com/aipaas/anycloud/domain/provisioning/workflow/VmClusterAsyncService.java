package com.aipaas.anycloud.domain.provisioning.workflow;

import io.aipaas.cluster.provisioning.api.ProvisioningRequest;
import java.util.concurrent.CompletableFuture;

public interface VmClusterAsyncService {

    CompletableFuture<Void> provisionClusterAsync(
            String provisioningId, String clusterName, ProvisioningRequest request);

    CompletableFuture<Void> destroyClusterAsync(String clusterName);

    /**
     * 워커 노드 수 조절 (Day-2 §1). workflow publish 가 아니라 Pulumi config 갱신 + up 을
     * 직접 비동기 실행. workflow_retry_count 는 증가시키지 않음.
     */
    CompletableFuture<Void> scaleClusterAsync(String clusterName, int workerCount);
}
