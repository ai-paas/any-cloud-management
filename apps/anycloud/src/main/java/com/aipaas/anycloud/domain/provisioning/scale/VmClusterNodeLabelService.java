package com.aipaas.anycloud.domain.provisioning.scale;

import com.aipaas.anycloud.domain.provisioning.VmClusterEntity;
import java.util.Map;

/** Pulumi worker 인덱스를 K8s 노드 라벨로 부착해 두 시스템 간 매핑 명시화. */
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
