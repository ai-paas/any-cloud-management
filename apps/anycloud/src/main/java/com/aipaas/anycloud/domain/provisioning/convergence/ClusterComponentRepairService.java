package com.aipaas.anycloud.domain.provisioning.convergence;

import com.aipaas.anycloud.domain.provisioning.VmClusterEntity;

public interface ClusterComponentRepairService {

    /**
     * 구성 요소 즉시 재적용. 백오프 무시, 시도 횟수 초기화.
     *
     * <p>운영자 명시 요청 경로. 조정 루프가 백오프로 1시간 후 재시도하는 상황에서 원인 조치
     * 직후 확인할 수단 필요.
     */
    void repair(VmClusterEntity vmCluster, ComponentType type);

    /**
     * 조정 루프용. {@link #repair} 와 달리 시도 횟수 누적, 백오프 적용. 예외 미전파.
     *
     * @return 이번 주기에 적용을 시도한 경우 true
     */
    boolean repairIfDue(VmClusterEntity vmCluster, ComponentType type);
}
