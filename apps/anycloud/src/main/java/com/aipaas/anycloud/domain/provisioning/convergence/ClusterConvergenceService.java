package com.aipaas.anycloud.domain.provisioning.convergence;

import com.aipaas.anycloud.domain.provisioning.VmClusterEntity;

public interface ClusterConvergenceService {

    /** REQUIRED 컴포넌트가 전부 충족될 때까지 제한 횟수 안에서 재관측. */
    boolean convergeWithinBudget(VmClusterEntity vmCluster);
}
