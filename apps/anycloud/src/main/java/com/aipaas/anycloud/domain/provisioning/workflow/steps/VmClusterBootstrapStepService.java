package com.aipaas.anycloud.domain.provisioning.workflow.steps;

public interface VmClusterBootstrapStepService {

    void execute(String vmClusterId, String clusterName);
}
