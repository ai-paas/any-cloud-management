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
 * fleet upgrade 현황 조회와 wave 지정. 실제 진행은 {@link FleetUpgradeOrchestrator} 담당.
 *
 * <p>cluster 별 upgrade_wave 는 CANARY, STAGING, GENERAL, PAUSED 중 하나. 신규 등록 cluster 는
 * GENERAL. 문제가 있는 cluster 를 PAUSED 로 지정하면 모든 wave 에서 제외.
 *
 * <p>{@link #preview} 로 wave 분포와 agent_version 분포 확인 후 계획 수립.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FleetUpgradeServiceImpl implements FleetUpgradeService {

    private final ClusterAgentRepository clusterAgentRepository;

    /** Fleet upgrade 가시화. wave 별로 cluster 를 그룹화 + 각 cluster 의 현재 agent_version. */
    @Override
    @Transactional(readOnly = true)
    public FleetPreview preview() {
        List<ClusterAgentEntity> all = clusterAgentRepository.findAll();
        // cluster_name → ClusterEntry (versions 집합 + wave). versions list 는 record 의 accessor
        // 로 받은 동일 reference (record 는 shallow immutable: list 자체는 mutable).
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
     * @throws CustomException ENTITY_NOT_FOUND: cluster 의 agent row 가 0건.
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
    // 상위 type 그대로 사용: 같은 패키지라 simple name 으로 참조 가능.
}
