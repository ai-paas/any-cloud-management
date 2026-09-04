package com.aipaas.anycloud.domain.provisioning.convergence;

public interface ClusterConvergenceOrchestrator {

    /** READY / DEGRADED 클러스터를 한 바퀴 관측하고 상태를 조정한다. */
    void drive();
}
