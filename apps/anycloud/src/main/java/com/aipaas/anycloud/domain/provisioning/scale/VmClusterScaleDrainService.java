package com.aipaas.anycloud.domain.provisioning.scale;

import com.aipaas.anycloud.domain.provisioning.VmClusterEntity;
import java.util.Map;

/**
 * Scale-down 시 사라질 worker 노드를 자동으로 cordon/drain (Day-2 §1 후속 #2).
 * <p>
 * Pulumi 가 인스턴스를 삭제하기 전에 K8s 차원에서 워크로드를 evict 해 가용성 손실을 최소화.
 * master 에 SSH 로 접속해 {@code kubectl drain} 을 실행하며, 실패해도 Pulumi 단계는 진행한다
 * (운영자가 사전 drain 한 경우 대비). PoC 단계의 best-effort 구현.
 */
public interface VmClusterScaleDrainService {

    /**
     * 가장 최근 생성된 worker 노드 {@code removeCount} 개를 drain.
     *
     * @param vmCluster    대상 클러스터 entity
     * @param outputs      현재 Pulumi stack outputs (nodes 배열 참조)
     * @param removeCount  drain 할 worker 수 (≥ 1)
     * @return drain 이 시도된 node 이름 목록 (성공/실패와 무관)
     */
    java.util.List<String> drainExcessWorkers(VmClusterEntity vmCluster, Map<String, Object> outputs, int removeCount);
}
