package com.aipaas.anycloud.domain.addon;

import com.aipaas.anycloud.domain.addon.api.response.AddonStatusResponse;
import com.aipaas.anycloud.domain.addon.model.AddonSpec;
import com.aipaas.anycloud.domain.operation.OperationEntity;
import java.util.List;

/**
 * Cluster addon CRUD service — REST controller 의 facade.
 *
 * <p>주요 흐름:
 * <ul>
 *   <li>{@code create} — spec resolve → entity save → cluster ACTIVE 면 즉시 enqueue, 아니면 PENDING 으로 대기.</li>
 *   <li>{@code retry} — FAILED → re-enqueue.</li>
 *   <li>{@code delete} — DELETING + uninstall queue publish.</li>
 *   <li>{@code reenqueueAllForCluster} — admin backfill (이미 ACTIVE 인 cluster 에 enabled 인 PENDING/FAILED 모두 enqueue).</li>
 * </ul>
 */
public interface AddonService {

    List<AddonStatusResponse> list(String clusterId);

    AddonStatusResponse get(String clusterId, String addonId);

    /**
     * Addon 추가. cluster ACTIVE 면 즉시 enqueue, 아니면 PENDING — cluster ACTIVE 전환 시 listener 가 enqueue.
     * 동일 (cluster, namespace, releaseName) 중복은 unique key 로 차단.
     */
    AddonStatusResponse create(String clusterId, AddonSpec spec);

    /** FAILED → re-enqueue. PENDING/SUCCEEDED 등 다른 state 는 StateConflictException. */
    OperationEntity retry(String clusterId, String addonId);

    /** uninstall enqueue + DELETING state. row 는 hard delete 안함 — operation 이력 보존. */
    OperationEntity delete(String clusterId, String addonId);

    /** Backfill — 이미 ACTIVE 인 cluster 에 신규 addon 추가 또는 FAILED retry 일괄. */
    int reenqueueAllForCluster(String clusterId);
}
