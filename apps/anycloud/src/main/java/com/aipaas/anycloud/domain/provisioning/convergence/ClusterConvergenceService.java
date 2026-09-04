package com.aipaas.anycloud.domain.provisioning.convergence;

import com.aipaas.anycloud.domain.provisioning.VmClusterEntity;

public interface ClusterConvergenceService {

    /**
     * REQUIRED 컴포넌트가 전부 충족될 때까지 제한 횟수 안에서 재관측.
     *
     * <p>충족되면 true. 미충족 또는 확인 불가면 false — 호출자가 DEGRADED 로 보내고 조정 루프에
     * 넘긴다. 예외를 던지지 않는다.
     */
    boolean convergeWithinBudget(VmClusterEntity vmCluster);
}
