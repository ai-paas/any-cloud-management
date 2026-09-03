package com.aipaas.anycloud.domain.provisioning.scale;

import com.aipaas.anycloud.domain.provisioning.VmClusterEntity;
import java.util.Map;

/**
 * Pulumi worker 인덱스를 K8s 노드 라벨로 부착해 두 시스템 간 매핑 명시화.
 *
 * <p>Pulumi 는 stack state 의 {@code workers[N]} 배열, K8s 는 hostname/IP 기준으로 노드 식별 —
 * 두 식별자가 일치한다는 보장 없어 scale-down/delete 시 대상 노드 특정 어려움. privateIp →
 * K8s InternalIP 매칭으로 {@code anycloud.aipaas/pulumi-index=worker-N} 라벨 부착,
 * {@link VmClusterScaleDrainService} 의 drain 대상 선정 및 노드별 cleanup 의 식별자로 활용.
 */
public interface VmClusterNodeLabelService {

    /** Pulumi worker 인덱스 보존용 K8s 노드 라벨 키. */
    String PULUMI_INDEX_LABEL = "anycloud.aipaas/pulumi-index";

    /**
     * outputs.nodes[] worker 들을 K8s 노드와 매칭해 pulumi-index 라벨 부착. idempotent
     * ({@code kubectl label --overwrite}), best-effort — 매칭 실패 인덱스는 skip + log.
     *
     * @param vmCluster 대상 클러스터 entity
     * @param outputs   현재 Pulumi stack outputs
     * @return 라벨 부착 성공한 K8s 노드 이름 → pulumi-index 라벨값(예: "worker-2") 매핑
     */
    Map<String, String> reconcilePulumiIndexLabels(VmClusterEntity vmCluster, Map<String, Object> outputs);
}
