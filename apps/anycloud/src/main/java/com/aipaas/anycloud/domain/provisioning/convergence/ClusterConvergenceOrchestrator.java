package com.aipaas.anycloud.domain.provisioning.convergence;

public interface ClusterConvergenceOrchestrator {

    /** READY, DEGRADED 클러스터 전체를 1회 관측 후 상태 조정. */
    void drive();

    /**
     * 클러스터 단건 조정. 정기 주기 대기 없이 즉시 확인이 필요할 때 사용.
     *
     * <p>{@link #drive()} 와 달리 스케줄러 락 미사용. 단일 클러스터만 대상이고 상태 전이는
     * 동일 값이면 무동작이므로 정기 조정과 중복 실행돼도 안전.
     */
    void driveOne(String clusterName);
}
