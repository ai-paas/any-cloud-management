package com.aipaas.anycloud.domain.provisioning.command;

import com.aipaas.anycloud.domain.provisioning.api.request.ProvisionClusterRequest;
import org.springframework.http.HttpStatus;

public interface VmClusterCommandService {

    HttpStatus createVmCluster(ProvisionClusterRequest cluster);

    HttpStatus retryVmClusterRegistration(String clusterName);

    /**
     * BLOCKED 또는 FAILED 상태의 VM 클러스터를 명시적으로 unblock 하고 lastFailedStep 부터 재시도.
     * workflowRetryCount 를 0 으로 초기화하고 적절한 step 메시지를 새 messageId 로 publish.
     * <p>
     * 지원: BOOTSTRAP / VERIFY step. PROVISION 실패는 인프라가 부분 생성된 상태일 가능성이 커
     * DELETE 후 재생성을 권장한다.
     */
    HttpStatus retryVmClusterWorkflow(String clusterName);

    /**
     * Day-2 §1. 워커 노드 수 조절. 비동기 ACCEPTED 반환.
     * READY 상태에서만 허용하며 scale-down 시 운영자 사전 drain 권장.
     */
    HttpStatus scaleVmCluster(String clusterName, int workerCount);

    HttpStatus deleteVmCluster(String clusterName);
}
