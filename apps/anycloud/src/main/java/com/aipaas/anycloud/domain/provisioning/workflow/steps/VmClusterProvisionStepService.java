package com.aipaas.anycloud.domain.provisioning.workflow.steps;

import io.aipaas.cluster.provisioning.api.ProvisioningRequest;

public interface VmClusterProvisionStepService {

    void execute(String vmClusterId, String clusterName, ProvisioningRequest request);
}
