package com.aipaas.anycloud.domain.addon;

import com.aipaas.anycloud.domain.addon.model.AddonState;
import com.aipaas.anycloud.domain.addon.model.AddonType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * cluster_addon CRUD.
 *
 * <p>쿼리 패턴:
 * <ul>
 *   <li>findByClusterId — frontend addon 목록 + cluster ACTIVE listener.</li>
 *   <li>findByClusterIdAndState — listener 가 PENDING 만 enqueue.</li>
 *   <li>findByClusterIdAndNamespaceAndReleaseName — idempotency 검증 + update lookup.</li>
 * </ul>
 */
@Repository
public interface ClusterAddonRepository extends JpaRepository<ClusterAddonEntity, String> {

    List<ClusterAddonEntity> findByClusterId(String clusterId);

    List<ClusterAddonEntity> findByClusterIdAndState(String clusterId, AddonState state);

    List<ClusterAddonEntity> findByClusterIdAndStateInAndEnabledTrue(String clusterId, List<AddonState> states);

    Optional<ClusterAddonEntity> findByClusterIdAndNamespaceAndReleaseName(
            String clusterId, String namespace, String releaseName);

    List<ClusterAddonEntity> findByState(AddonState state);

    List<ClusterAddonEntity> findByAddonType(AddonType type);
}
