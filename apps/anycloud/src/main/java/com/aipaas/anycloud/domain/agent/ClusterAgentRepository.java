package com.aipaas.anycloud.domain.agent;

import com.aipaas.anycloud.domain.agent.model.ClusterAgentStatus;
import com.aipaas.anycloud.domain.agent.model.ClusterAgentUpgradeStatus;
import com.aipaas.anycloud.domain.agent.model.ClusterAgentUpgradeWave;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.stereotype.Repository;

@Repository
public interface ClusterAgentRepository
        extends JpaRepository<ClusterAgentEntity, String>,
                JpaSpecificationExecutor<ClusterAgentEntity> {

    /** Runtime stream 인증 시 token hash 로 lookup. revoked_at 별도 체크. */
    Optional<ClusterAgentEntity> findByIdentityTokenHash(String identityTokenHash);

    /** 같은 cluster 의 active agent 들. HA replica 식별. */
    List<ClusterAgentEntity> findByClusterNameAndStatus(String clusterName, ClusterAgentStatus status);

    /**
     * 특정 status 의 모든 agent 를 DB-side 필터링. {@code findAll().filter(ACTIVE)} 로
     * in-memory 필터링하면 수천 agent 환경에서 heap pressure 가 발생하므로 DB 단에서 처리.
     */
    List<ClusterAgentEntity> findByStatus(ClusterAgentStatus status);

    /** 같은 cluster_name + agent_instance_id 조합 — 멱등 upsert 용. */
    Optional<ClusterAgentEntity> findByClusterNameAndAgentInstanceId(String clusterName, String agentInstanceId);

    List<ClusterAgentEntity> findByClusterName(String clusterName);

    /**
     * Cluster 삭제 시 cascade cleanup — 동일 cluster_name 의 모든 agent row 정리.
     * Spring Data JPA derived delete (auto-implementation).
     *
     * @return 삭제된 row 수.
     */
    @Modifying
    long deleteByClusterName(String clusterName);

    /**
     * Fleet upgrade orchestrator 의 N+1 회피 — 여러 wave 의 agent 를 한 번에 조회.
     * {@code drive()} 가 매 PLANNED→RUNNING 전환 시 totalClusters 계산용으로 호출.
     */
    List<ClusterAgentEntity> findByUpgradeWaveIn(List<ClusterAgentUpgradeWave> waves);

    /** Fleet upgrade — 같은 wave 의 모든 agent (HA replica 포함). orchestrator 가 cluster 기준 dedup. */
    List<ClusterAgentEntity> findByUpgradeWave(ClusterAgentUpgradeWave wave);

    /**
     * AgentUpgradeProgressMonitor.sweep 전용 scoped finder — SQL WHERE upgrade_status IN (...)
     * 로 DB index 활용 (agent 1000+ 환경에서 heap 전송량 최소화).
     */
    List<ClusterAgentEntity> findByUpgradeStatusIn(List<ClusterAgentUpgradeStatus> statuses);
}
