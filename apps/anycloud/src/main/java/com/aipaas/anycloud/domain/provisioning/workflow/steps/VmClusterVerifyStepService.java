package com.aipaas.anycloud.domain.provisioning.workflow.steps;

public interface VmClusterVerifyStepService {

    void execute(String vmClusterId, String clusterName);
}
