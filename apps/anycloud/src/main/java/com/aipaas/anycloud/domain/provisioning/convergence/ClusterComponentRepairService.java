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

    /**
     * 조정 루프용 자동 재적용. 백오프가 남아 있으면 아무것도 하지 않는다.
     *
     * <p>{@link #repair} 는 운영자가 원인을 고쳤다는 전제로 시도 회계를 초기화하지만, 여기서는
     * 누적한다 — 같은 이유로 계속 실패하는 컴포넌트를 5분마다 무한히 재시도하면 안 된다.
     *
     * <p>예외를 던지지 않는다. 한 컴포넌트의 실패가 나머지 조정을 멈추면 안 된다.
     *
     * @return 이번 주기에 적용을 시도했으면 true
     */
    boolean repairIfDue(VmClusterEntity vmCluster, ComponentType type);
}
