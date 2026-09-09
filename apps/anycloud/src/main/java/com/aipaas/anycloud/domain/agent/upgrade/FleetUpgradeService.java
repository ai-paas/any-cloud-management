package com.aipaas.anycloud.domain.agent.upgrade;

import com.aipaas.anycloud.domain.agent.model.ClusterAgentUpgradeWave;
import java.util.List;
import java.util.Map;

/** Fleet upgrade staggered rollout — agent 신버전 배포의 wave-based 순차 처리. */
public interface FleetUpgradeService {

    /** Fleet upgrade 가시화. wave 별 cluster 분포 + agent_version 분포 + per-cluster 상세. */
    FleetPreview preview();

    /**
     * 단일 cluster 의 upgrade_wave 변경. HA replica 가 여러 row 인 경우 모두 같은 wave 로 sync.
     *
     * @throws com.aipaas.anycloud.common.error.exception.CustomException
     *         ENTITY_NOT_FOUND — cluster 의 agent row 가 0건.
     */
    void setWave(String clusterName, ClusterAgentUpgradeWave wave);

    /**
     * Preview 결과.
     *
     * @param totalClusters  cluster_agent 의 cluster 수 (dedup by name).
     * @param waveCounts     wave → cluster count.
     * @param versionCounts  agent_version → cluster count (HA 의 여러 version 은 각각 count).
     * @param byWave         wave → cluster entry list (preview UI 가 화면에 표시).
     */
    record FleetPreview(
            int totalClusters,
            Map<ClusterAgentUpgradeWave, Long> waveCounts,
            Map<String, Long> versionCounts,
            Map<ClusterAgentUpgradeWave, List<ClusterEntry>> byWave) {}

    /** Preview 의 cluster 단위 entry — wave + 현재 active versions. */
    record ClusterEntry(String clusterName, ClusterAgentUpgradeWave wave, List<String> versions) {}
}
