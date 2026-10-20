package com.aipaas.anycloud.domain.provisioning.convergence;

import com.aipaas.anycloud.domain.provisioning.VmClusterEntity;
import java.util.List;

public interface ClusterComponentObserver {

    /**
     * 이 클러스터에 해당하는 컴포넌트를 전부 probe 하고 결과를 영속화.
     *
     * <p>클러스터 상태는 바꾸지 않는다. 예외를 던지지 않는다: 관측 실패가 호출자의 워크플로우를
     * 중단시키면 안 된다. 관측 불가 시 빈 리스트.
     */
    List<ComponentObservation> observe(VmClusterEntity vmCluster);

    /** probe 없이 저장된 상태만 조회. 조회 API 의 요청별 SSH 연결 금지. */
    List<ComponentObservation> currentComponents(String vmClusterId);
}
