package com.aipaas.anycloud.domain.provisioning.workflow;

public interface VmClusterWorkflowPublisher {

    void publishProvision(VmClusterWorkflowMessage message);

    void publishBootstrap(VmClusterWorkflowMessage message);

    void publishVerify(VmClusterWorkflowMessage message);

    void publishDestroy(VmClusterWorkflowMessage message);
}
