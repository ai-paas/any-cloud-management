package com.aipaas.anycloud.domain.provisioning.convergence;

import com.aipaas.anycloud.domain.provisioning.VmClusterEntity;

public interface ClusterComponentRepairService {

    /**
     * 구성 요소를 즉시 재적용. 백오프를 무시하고 시도 회계를 초기화한다.
     *
     * <p>운영자의 명시 요청 경로다. 조정 루프가 백오프 때문에 한 시간 뒤에나 다시 시도하는 상황에서
     * 원인을 고친 직후 바로 확인할 수단이 필요하다.
     */
    void repair(VmClusterEntity vmCluster, ComponentType type);
}
