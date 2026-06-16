package com.aipaas.anycloud.domain.provisioning;

import com.aipaas.anycloud.domain.provisioning.model.VmClusterStateHistory;
import java.util.List;

/**
 * VmCluster state transition 이력 조회 (read-only).
 *
 * <p>Impl: {@link com.aipaas.anycloud.domain.provisioning.internal.VmClusterStateHistoryQueryServiceImpl}.
 *
 * <p>{@code *Entity} 메서드와 {@code *Domain*} 메서드가 양립합니다.
 * 새 caller 는 domain 변형 사용 (immutable record). entity 변형은 점진 deprecate.
 */
public interface VmClusterStateHistoryQueryService {

    /**
     * Cluster 의 최근 state transition 이력 (최신 → 과거).
     *
     * @param clusterName cluster ID
     * @param pageSize    1..500 (controller 가 검증)
     */
    List<VmClusterStateHistoryEntity> listRecent(String clusterName, int pageSize);

    /** {@link #listRecent(String, int)} 의 domain 변형. */
    List<VmClusterStateHistory> listRecentDomain(String clusterName, int pageSize);
}
