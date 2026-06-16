package com.aipaas.anycloud.domain.provisioning.scale;

import com.aipaas.anycloud.domain.provisioning.VmClusterEntity;
import java.util.Map;

/**
 * Pulumi 의 worker 인덱스를 K8s 노드 라벨에 부착해 두 시스템간 매핑을 명시화한다.
 * <p>
 * 배경: Pulumi 는 stack state 의 {@code workers[N]} 배열로 인스턴스를 추적하고,
 * K8s 는 hostname/IP 기준으로 노드를 식별한다. 두 식별자가 일치한다는 보장이 없어
 * scale-down 이나 delete 시 어떤 K8s 노드가 사라질지 정확히 알기 어려웠다.
 * <p>
 * 본 서비스는 Pulumi outputs 의 {@code nodes[].role}({@code "worker-N"}) 과
 * {@code nodes[].privateIp} 를 K8s 측 InternalIP 와 매칭해
 * 라벨 {@code anycloud.aipaas/pulumi-index=worker-N} 을 부착한다. 모든 작업은 idempotent
 * ({@code kubectl label --overwrite}) 이며, K8s 측 정보가 master 에서 즉시 조회 가능한 시점에
 * 호출하면 된다.
 * <p>
 * 부착된 라벨은 {@link VmClusterScaleDrainService} 가 drain 대상 선정에,
 * 향후 delete 시점에 노드별 cleanup 로직에 활용한다.
 */
public interface VmClusterNodeLabelService {

    /** K8s 노드 라벨 키. Pulumi 의 worker 인덱스를 보존하기 위한 백엔드 전용 라벨. */
    String PULUMI_INDEX_LABEL = "anycloud.aipaas/pulumi-index";

    /**
     * outputs.nodes[] 의 worker 들을 순회하며 매칭되는 K8s 노드에 pulumi-index 라벨을 부착한다.
     * <p>
     * 매칭은 privateIp → K8s InternalIP 1:1 로 수행한다. 매칭 실패한 인덱스는 skip(로그만 남김).
     * 본 메서드는 best-effort 로, 라벨 부착 실패 시에도 예외를 전파하지 않는다.
     *
     * @param vmCluster 대상 클러스터 entity
     * @param outputs   현재 Pulumi stack outputs
     * @return 라벨 부착에 성공한 K8s 노드 이름 → pulumi-index 라벨값(예: "worker-2") 매핑
     */
    Map<String, String> reconcilePulumiIndexLabels(VmClusterEntity vmCluster, Map<String, Object> outputs);
}
