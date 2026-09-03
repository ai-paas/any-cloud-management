package com.aipaas.anycloud.domain.agent.upgrade;

import com.aipaas.anycloud.common.error.enums.ErrorCode;
import com.aipaas.anycloud.common.error.exception.CustomException;
import com.aipaas.anycloud.domain.agent.ClusterAgentEntity;
import com.aipaas.anycloud.domain.agent.ClusterAgentRepository;
import com.aipaas.anycloud.domain.agent.model.ClusterAgentUpgradeWave;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Fleet upgrade staggered rollout — agent 신버전 배포의 wave-based 순차 처리.
 *
 * <p>운영 워크플로우:
 * <ol>
 *   <li>운영자가 각 cluster 의 upgrade_wave 지정 (CANARY / STAGING / GENERAL / PAUSED).
 *       기본값 GENERAL — 신규 등록 cluster 는 일반 wave.</li>
 *   <li>{@link #preview} 로 현재 fleet 의 wave 분포 + agent_version 분포 확인.</li>
 *   <li>운영자가 CANARY wave 만 신버전으로 upgrade trigger (별도 sprint 의 실제 trigger 로직).</li>
 *   <li>CANARY 안정화 후 STAGING → GENERAL 순차 진행.</li>
 *   <li>이상 cluster 는 PAUSED 로 빼서 격리.</li>
 * </ol>
 *
 * <p> (본 sprint): preview + wave 지정. (다음): orchestrator (실제 helm upgrade
 * trigger via agent APPLY_MANIFEST + 진행률 추적 + 자동 abort).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FleetUpgradeServiceImpl implements FleetUpgradeService {

    private final ClusterAgentRepository clusterAgentRepository;

    /**
     * Fleet upgrade 가시화. wave 별로 cluster 를 그룹화 + 각 cluster 의 현재 agent_version.
     *
     * <p>HA replica (같은 cluster_name 의 여러 row) 는 dedup — version 이 다르면 정상 (rolling
     * 중) 인지 stuck 인지 운영자 판단. preview 는 모든 instance 의 version 을 set 으로 노출.
     */
    @Override
    @Transactional(readOnly = true)
    public FleetPreview preview() {
        List<ClusterAgentEntity> all = clusterAgentRepository.findAll();
        // cluster_name → ClusterEntry (versions 집합 + wave). versions list 는 record 의 accessor
        // 로 받은 동일 reference (record 는 shallow immutable — list 자체는 mutable).
        Map<String, ClusterEntry> byCluster = new TreeMap<>();
        for (ClusterAgentEntity e : all) {
            ClusterEntry entry = byCluster.computeIfAbsent(
                    e.getClusterName(),
                    k -> new ClusterEntry(
                            k,
                            e.getUpgradeWave() == null ? ClusterAgentUpgradeWave.GENERAL : e.getUpgradeWave(),
                            new ArrayList<>()));
            String v = e.getAgentVersion();
            if (v != null && !v.isBlank() && !entry.versions().contains(v)) {
                entry.versions().add(v);
            }
        }

        // Wave 별 그룹화 (orderRank 순). Map 의 iteration 순서가 wave 우선순위.
        Map<ClusterAgentUpgradeWave, List<ClusterEntry>> grouped = new LinkedHashMap<>();
        List<ClusterEntry> sorted = new ArrayList<>(byCluster.values());
        sorted.sort(Comparator.<ClusterEntry>comparingInt(e -> e.wave().orderRank())
                .thenComparing(ClusterEntry::clusterName));
        for (ClusterEntry e : sorted) {
            grouped.computeIfAbsent(e.wave(), k -> new ArrayList<>()).add(e);
        }

        // Aggregate counts.
        Map<ClusterAgentUpgradeWave, Long> waveCounts = new LinkedHashMap<>();
        for (var entry : grouped.entrySet()) {
            waveCounts.put(entry.getKey(), (long) entry.getValue().size());
        }
        Map<String, Long> versionCounts = new HashMap<>();
        for (ClusterEntry e : byCluster.values()) {
            for (String v : e.versions()) {
                versionCounts.merge(v, 1L, Long::sum);
            }
        }

        return new FleetPreview(byCluster.size(), waveCounts, versionCounts, grouped);
    }

    /**
     * 단일 cluster 의 upgrade_wave 변경. HA replica 가 여러 row 인 경우 모두 같은 wave 로 sync.
     *
     * @throws CustomException ENTITY_NOT_FOUND — cluster 의 agent row 가 0건.
     */
    @Override
    @Transactional
    public void setWave(String clusterName, ClusterAgentUpgradeWave wave) {
        if (clusterName == null || clusterName.isBlank()) {
            throw new CustomException("cluster name required", ErrorCode.INVALID_INPUT_VALUE);
        }
        if (wave == null) {
            throw new CustomException("wave required", ErrorCode.INVALID_INPUT_VALUE);
        }
        List<ClusterAgentEntity> rows = clusterAgentRepository.findByClusterName(clusterName);
        if (rows.isEmpty()) {
            throw new CustomException("No agent rows for cluster " + clusterName, ErrorCode.ENTITY_NOT_FOUND);
        }
        for (ClusterAgentEntity e : rows) {
            e.setUpgradeWave(wave);
        }
        clusterAgentRepository.saveAll(rows);
        log.info("Fleet upgrade wave set: cluster={} wave={} affected_rows={}", clusterName, wave, rows.size());
    }

    // Response DTOs 는 {@link FleetUpgradeService} interface 에 record 로 노출. impl 안에서는
    // 상위 type 그대로 사용 — 같은 패키지라 simple name 으로 참조 가능.
}
