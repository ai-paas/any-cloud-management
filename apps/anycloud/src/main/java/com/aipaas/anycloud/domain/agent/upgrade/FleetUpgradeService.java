package com.aipaas.anycloud.domain.agent.upgrade;

import com.aipaas.anycloud.domain.agent.model.ClusterAgentUpgradeWave;
import java.util.List;
import java.util.Map;

/**
 * Fleet upgrade staggered rollout — agent 신버전 배포의 wave-based 순차 처리.
 *
 * <p>본 interface 는 cluster_agent.upgrade_wave 메타데이터 관리 + fleet 가시화의 진입점. 실제
 * 신버전 trigger 는 {@link AgentUpgradeService} (single-cluster) 또는
 * {@link FleetUpgradeOrchestrator} (wave 기반 fleet-wide) 가 책임.
 *
 * <p>구현체: {@link FleetUpgradeServiceImpl}. mock 기반 unit test 는 본 interface 를 통해
 * 임의의 cluster fleet shape 을 stub 가능.
 */
public interface FleetUpgradeService {

    /**
     * Fleet upgrade 가시화. wave 별 cluster 분포 + agent_version 분포 + per-cluster 상세.
     *
     * <p>HA replica (같은 cluster_name 의 여러 row) 는 dedup. 응답의 {@code byWave} map 은
     * wave priority 순 (CANARY → STAGING → GENERAL → PAUSED) 으로 정렬.
     */
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
