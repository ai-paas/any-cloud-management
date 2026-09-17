package com.aipaas.anycloud.domain.provisioning.convergence.internal;

import com.aipaas.anycloud.domain.provisioning.VmClusterEntity;
import com.aipaas.anycloud.domain.provisioning.convergence.ClusterComponentRepairService;
import com.aipaas.anycloud.domain.provisioning.convergence.ComponentType;
import com.aipaas.anycloud.domain.provisioning.workflow.support.VmClusterWorkflowSupportService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 컨트롤러가 cluster 조회와 재적용을 한 번에 부르도록 하는 얇은 계층. */
@Service
@RequiredArgsConstructor
public class ClusterComponentRepairFacade {

    private final VmClusterWorkflowSupportService workflowSupportService;
    private final ClusterComponentRepairService repairService;

    public void repairByClusterName(String clusterName, ComponentType type) {
        VmClusterEntity vmCluster = workflowSupportService.getLatestVmCluster(clusterName);
        repairService.repair(vmCluster, type);
    }
}
