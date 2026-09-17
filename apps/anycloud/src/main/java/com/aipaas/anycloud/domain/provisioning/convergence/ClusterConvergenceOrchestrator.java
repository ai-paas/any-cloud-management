package com.aipaas.anycloud.domain.provisioning.convergence;

public interface ClusterConvergenceOrchestrator {

    /** READY / DEGRADED 클러스터를 한 바퀴 관측하고 상태를 조정한다. */
    void drive();

    /**
     * 클러스터 하나만 조정한다. 정기 주기를 기다리지 않고 지금 봐야 할 때 쓴다.
     *
     * <p>{@link #drive()} 와 달리 스케줄러 락을 잡지 않는다 — 한 클러스터만 건드리고,
     * 상태 전이는 같은 값이면 아무것도 하지 않아 정기 조정과 겹쳐도 안전하다.
     */
    void driveOne(String clusterName);
}
