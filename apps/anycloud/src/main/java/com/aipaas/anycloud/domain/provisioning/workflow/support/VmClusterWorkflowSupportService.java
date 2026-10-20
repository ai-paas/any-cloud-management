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

    /** REQUIRED 구성 요소 미충족으로 READY 대신 DEGRADED 종료. 이후 처리는 조정 루프 담당. */
    void markDegraded(VmClusterEntity vmCluster);

    void markDeleteCompleted(VmClusterEntity vmCluster);

    void fail(VmClusterEntity vmCluster, String clusterName, Exception e);

    void failWithDiagnostics(VmClusterEntity vmCluster, String clusterName, Exception e);
}
