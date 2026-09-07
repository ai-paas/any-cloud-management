package com.aipaas.anycloud.domain.provisioning.workflow;

/** VM 클러스터 워크플로우 진입점. */
public interface VmClusterWorkflowOrchestrator {

    void provisionInfrastructure(VmClusterWorkflowMessage message);

    void bootstrapCluster(VmClusterWorkflowMessage message);

    void verifyCluster(VmClusterWorkflowMessage message);

    void destroyCluster(VmClusterWorkflowMessage message);
}
