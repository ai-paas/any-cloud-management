package com.aipaas.anycloud.domain.provisioning.workflow.support;

import com.aipaas.anycloud.domain.provisioning.VmClusterEntity;
import com.aipaas.anycloud.domain.provisioning.model.VmClusterStatus;
import com.aipaas.anycloud.domain.provisioning.workflow.VmClusterWorkflowStep;

public interface VmClusterWorkflowSupportService {

    VmClusterEntity getVmClusterById(String vmClusterId, String clusterName);

    VmClusterEntity getLatestVmCluster(String clusterName);

    void markStepStarted(
            VmClusterEntity vmCluster, VmClusterWorkflowStep step, VmClusterStatus status, boolean incrementRetryCount);

    void markStepSucceeded(VmClusterEntity vmCluster, VmClusterWorkflowStep step);

    void markReady(VmClusterEntity vmCluster);

    /** REQUIRED 구성 요소 미충족으로 READY 대신 DEGRADED 로 종료. 조정 루프가 이어받는다. */
    void markDegraded(VmClusterEntity vmCluster);

    void markDeleteCompleted(VmClusterEntity vmCluster);

    void fail(VmClusterEntity vmCluster, String clusterName, Exception e);

    void failWithDiagnostics(VmClusterEntity vmCluster, String clusterName, Exception e);
}
