package com.aipaas.anycloud.domain.provisioning.scale;

import com.aipaas.anycloud.domain.provisioning.VmClusterEntity;
import java.util.Map;

/**
 * Scale-down 대상 worker 노드의 자동 cordon, drain.
 *
 * <p>Pulumi 의 인스턴스 삭제 전에 K8s 계층에서 워크로드를 evict 해 가용성 손실 축소.
 * master 에 SSH 접속해 {@code kubectl drain} 실행하며, 실패 시에도 Pulumi 단계는 진행
 * (운영자 사전 drain 대응). best-effort 구현.
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
