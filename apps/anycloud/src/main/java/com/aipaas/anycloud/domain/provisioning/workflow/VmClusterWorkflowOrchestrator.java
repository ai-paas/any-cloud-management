package com.aipaas.anycloud.domain.provisioning.workflow;

/**
 * VM 클러스터 워크플로우 진입점.
 * <p>
 * 각 메서드는 {@link VmClusterWorkflowMessage} 전체를 받아 멱등성 가드를 적용한 뒤
 * 해당 step service 로 위임. Listener / Local publisher 양쪽에서 동일 호출이 가능하다.
 */
public interface VmClusterWorkflowOrchestrator {

    void provisionInfrastructure(VmClusterWorkflowMessage message);

    void bootstrapCluster(VmClusterWorkflowMessage message);

    void verifyCluster(VmClusterWorkflowMessage message);

    void destroyCluster(VmClusterWorkflowMessage message);
}
