package com.aipaas.anycloud.domain.agent.upgrade;

import com.aipaas.anycloud.common.error.enums.ErrorCode;
import com.aipaas.anycloud.common.error.exception.CustomException;
import com.aipaas.anycloud.domain.agent.ClusterAgentEntity;
import com.aipaas.anycloud.domain.agent.ClusterAgentRepository;
import com.aipaas.anycloud.domain.agent.FleetUpgradeRunRepository;
import com.aipaas.anycloud.domain.agent.model.ClusterAgentUpgradeStatus;
import com.aipaas.anycloud.domain.agent.model.ClusterAgentUpgradeWave;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Fleet-wide upgrade 의 wave 순차 처리 — 의 핵심.
 *
 * <p>흐름:
 * <ol>
 *   <li>운영자가 {@link #submit} 호출 → {@code fleet_upgrade_run} row (PLANNED) 생성</li>
 *   <li>15초 주기 {@link #drive} (ShedLock-protected) 가 PLANNED row 발견 → RUNNING 전환</li>
 *   <li>현재 wave 의 모든 cluster (PAUSED 제외) 에 대해 {@code AgentUpgradeService.upgradeCluster}
 *       호출. {@code concurrency} 만큼 동시 trigger. 이미 진행 중이거나 같은 image 면 skip.</li>
 *   <li>Wave 의 모든 cluster 가 SUCCEEDED 또는 FAILED 가 될 때까지 wait. Tick 마다 진행률 확인.</li>
 *   <li>Wave failure rate &gt; {@code failureThreshold} 이면 ABORTED. 아니면 다음 wave.</li>
 *   <li>모든 wave 종료 → COMPLETED.</li>
 * </ol>
 *
 * <p>Idempotency: 한 run 안에서 같은 cluster 를 여러 wave 가 trigger 하지 않는다 — cluster 의
 * upgrade_wave 가 한 가지뿐이라 자연스럽게 한 wave 에만 속함.
 *
 * <p>HA replica 의 backend pod 중 한 노드만 처리하도록 ShedLock 사용.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FleetUpgradeOrchestratorImpl implements FleetUpgradeOrchestrator {

    private final FleetUpgradeRunRepository runRepository;
    private final ClusterAgentRepository clusterAgentRepository;
    private final AgentUpgradeService agentUpgradeService;

    /**
     * 운영자가 trigger 한 fleet upgrade. PLANNED row 생성 후 scheduler 가 처리.
     *
     * @param targetImage    target docker image
     * @param waves          처리할 wave 목록 (예: [CANARY, STAGING, GENERAL])
     * @param concurrency    wave 안 동시 진행 cluster 수 (1-20)
     * @param failureThreshold 단일 wave failure rate (%) — 초과 시 자동 abort (1-100)
     * @param createdBy      audit (REST caller)
     */
    @Transactional
    @Override
    @com.aipaas.anycloud.domain.audit.Audited(
            action = "fleetUpgrade.submit",
            resourceType = "fleetUpgradeRun",
            resourceId = "#result?.runId()",
            summary = "'targetImage=' + #targetImage "
                    + "+ ', waves=' + #result?.wavesCsv() "
                    + "+ ', concurrency=' + #result?.concurrency() "
                    + "+ ', threshold=' + #result?.failureThreshold() + '%' "
                    + "+ ', createdBy=' + (#createdBy ?: 'unknown')")
    public FleetUpgradeRunEntity submit(
            String targetImage,
            List<ClusterAgentUpgradeWave> waves,
            int concurrency,
            int failureThreshold,
            String createdBy) {
        if (targetImage == null || targetImage.isBlank()) {
            throw new CustomException("targetImage required", ErrorCode.INVALID_INPUT_VALUE);
        }
        if (waves == null || waves.isEmpty()) {
            throw new CustomException("waves required", ErrorCode.INVALID_INPUT_VALUE);
        }
        // dedup + 자동 정렬 (CANARY → STAGING → GENERAL — PAUSED 는 거부)
        LinkedHashSet<ClusterAgentUpgradeWave> ordered = new LinkedHashSet<>();
        waves.stream()
                .sorted((a, b) -> Integer.compare(a.orderRank(), b.orderRank()))
                .forEach(w -> {
                    if (w == ClusterAgentUpgradeWave.PAUSED) {
                        throw new CustomException("PAUSED wave cannot be a target", ErrorCode.INVALID_INPUT_VALUE);
                    }
                    ordered.add(w);
                });
        int safeConcurrency = Math.max(1, Math.min(concurrency, 20));
        int safeThreshold = Math.max(1, Math.min(failureThreshold, 100));

        FleetUpgradeRunEntity run = FleetUpgradeRunEntity.builder()
                .runId(UUID.randomUUID().toString())
                .targetImage(targetImage)
                .wavesCsv(String.join(",", ordered.stream().map(Enum::name).toList()))
                .status(FleetUpgradeRunStatus.PLANNED)
                .concurrency(safeConcurrency)
                .failureThreshold(safeThreshold)
                .createdBy(createdBy)
                .build();
        runRepository.save(run);
        log.info(
                "Fleet upgrade submitted run_id={} target={} waves={} concurrency={} threshold={}%",
                run.getRunId(), targetImage, run.getWavesCsv(), safeConcurrency, safeThreshold);
        return run;
    }

    /**
     * 운영자가 명시적으로 abort. RUNNING / PAUSED 일 때만 의미.
     */
    @Transactional
    @Override
    @com.aipaas.anycloud.domain.audit.Audited(
            action = "fleetUpgrade.abort",
            resourceType = "fleetUpgradeRun",
            resourceId = "#runId",
            summary = "'reason=' + (#reason ?: 'none') " + "+ ', finalStatus=' + #result?.status()?.name()")
    public FleetUpgradeRunEntity abort(String runId, String reason) {
        FleetUpgradeRunEntity run = runRepository
                .findById(runId)
                .orElseThrow(() -> new CustomException("Run not found: " + runId, ErrorCode.ENTITY_NOT_FOUND));
        if (run.getStatus().isTerminal()) {
            return run; // no-op
        }
        run.setStatus(FleetUpgradeRunStatus.ABORTED);
        run.setCompletedAt(LocalDateTime.now());
        run.setLastError("Aborted by operator: " + (reason == null ? "(no reason)" : reason));
        return runRepository.save(run);
    }

    // ============================================================================
    // Scheduler — 15초 주기로 active run 처리.
    // ============================================================================

    @Scheduled(
            fixedDelayString = "${anycloud.fleet-upgrade.interval-ms:15000}",
            initialDelayString = "${anycloud.fleet-upgrade.initial-delay-ms:20000}")
    @SchedulerLock(name = "fleetUpgradeOrchestrator", lockAtMostFor = "PT5M", lockAtLeastFor = "PT5S")
    @Transactional
    public void drive() {
        List<FleetUpgradeRunEntity> active =
                runRepository.findByStatusIn(List.of(FleetUpgradeRunStatus.PLANNED, FleetUpgradeRunStatus.RUNNING));
        if (active.isEmpty()) {
            return;
        }
        for (FleetUpgradeRunEntity run : active) {
            try {
                driveOne(run);
            } catch (Exception e) {
                log.error("Fleet upgrade run drive failed run_id={}: {}", run.getRunId(), e.getMessage(), e);
                run.setStatus(FleetUpgradeRunStatus.ABORTED);
                run.setCompletedAt(LocalDateTime.now());
                run.setLastError("Orchestrator error: " + e.getMessage());
                runRepository.save(run);
            }
        }
    }

    /**
     * 한 run 의 진행을 한 단계 전진시킨다. 5개 helper 로 분리:
     * <ol>
     *   <li>{@link #transitionToRunningIfPlanned} — PLANNED → RUNNING, totalClusters 산정</li>
     *   <li>{@link #resolveOrInitCurrentWave} — currentWave 결정 (또는 ABORT/COMPLETED 종결)</li>
     *   <li>{@link #collectWavePrimaries} — wave 의 dedup cluster map</li>
     *   <li>{@link #triggerIdleClusters} — concurrency-bounded trigger</li>
     *   <li>{@link #advanceWaveOrFinishRun} — wave 완료 평가 + threshold + 다음 wave / COMPLETED</li>
     * </ol>
     * 각 helper 는 own state-transition 직후 자체 save (durability checkpoint). main 은 trigger 후
     * 최종 save 만.
     */
    private void driveOne(FleetUpgradeRunEntity run) {
        LocalDateTime now = LocalDateTime.now();
        List<ClusterAgentUpgradeWave> waves = parseWaves(run.getWavesCsv());

        transitionToRunningIfPlanned(run, waves, now);

        ClusterAgentUpgradeWave currentWave = resolveOrInitCurrentWave(run, waves, now);
        if (currentWave == null) {
            return; // ABORT 또는 COMPLETED — helper 가 이미 save + return.
        }

        Map<String, ClusterAgentEntity> primaryByCluster = collectWavePrimaries(currentWave);
        triggerIdleClusters(run, primaryByCluster);
        advanceWaveOrFinishRun(run, primaryByCluster, currentWave, waves, now);
        runRepository.save(run);
    }

    /** Step 1 — PLANNED 면 RUNNING 으로 전환 + totalClusters 계산 + save. */
    private void transitionToRunningIfPlanned(
            FleetUpgradeRunEntity run, List<ClusterAgentUpgradeWave> waves, LocalDateTime now) {
        if (run.getStatus() != FleetUpgradeRunStatus.PLANNED) {
            return;
        }
        run.setStatus(FleetUpgradeRunStatus.RUNNING);
        run.setStartedAt(now);
        // totalClusters — 모든 wave 의 cluster 합 (dedup). N 개 wave × per-wave query 가 N+1 이라
        // IN 으로 한 번에 조회 (운영자가 wave 5+ 선언한 fleet 에서 측정 가능한 지연 회피).
        Set<String> allTargets = clusterAgentRepository.findByUpgradeWaveIn(waves).stream()
                .map(ClusterAgentEntity::getClusterName)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        run.setTotalClusters(allTargets.size());
        runRepository.save(run);
        log.info("Fleet upgrade RUNNING run_id={} total_clusters={}", run.getRunId(), run.getTotalClusters());
    }

    /**
     * Step 2 — currentWave 결정.
     * <ul>
     *   <li>이미 set + valid → 반환.</li>
     *   <li>이미 set + invalid (DB corruption) → ABORTED + save + return null.</li>
     *   <li>empty + 첫 wave 가능 → 첫 wave set + save.</li>
     *   <li>empty + waves 도 empty → COMPLETED + save + return null (no-op run).</li>
     * </ul>
     *
     * @return 현재 wave, 또는 null (run 이 이미 terminal 상태).
     */
    private ClusterAgentUpgradeWave resolveOrInitCurrentWave(
            FleetUpgradeRunEntity run, List<ClusterAgentUpgradeWave> waves, LocalDateTime now) {
        if (run.getCurrentWave() != null && !run.getCurrentWave().isBlank()) {
            try {
                return ClusterAgentUpgradeWave.valueOf(run.getCurrentWave());
            } catch (IllegalArgumentException e) {
                log.warn("Unknown current_wave '{}' run_id={} — aborting", run.getCurrentWave(), run.getRunId());
                run.setStatus(FleetUpgradeRunStatus.ABORTED);
                run.setCompletedAt(now);
                run.setLastError("Unknown current_wave: " + run.getCurrentWave());
                runRepository.save(run);
                return null;
            }
        }
        if (waves.isEmpty()) {
            run.setStatus(FleetUpgradeRunStatus.COMPLETED);
            run.setCompletedAt(now);
            run.setCurrentWave(null);
            runRepository.save(run);
            log.info("Fleet upgrade COMPLETED (no waves) run_id={}", run.getRunId());
            return null;
        }
        ClusterAgentUpgradeWave first = waves.get(0);
        run.setCurrentWave(first.name());
        runRepository.save(run);
        return first;
    }

    /** Step 3 — 현재 wave 의 cluster 목록 — 중복 제거 (HA replica 의 첫 row 만). */
    private Map<String, ClusterAgentEntity> collectWavePrimaries(ClusterAgentUpgradeWave wave) {
        Map<String, ClusterAgentEntity> primaryByCluster = new HashMap<>();
        for (ClusterAgentEntity e : clusterAgentRepository.findByUpgradeWave(wave)) {
            primaryByCluster.putIfAbsent(e.getClusterName(), e);
        }
        return primaryByCluster;
    }

    /**
     * Step 4 — IDLE 인 cluster 중 최대 concurrency 개수 trigger. 자체 save 안 함 — main 의 final save
     * 가 trigger 의 skippedCount / failedCount 누적분 함께 commit.
     */
    private void triggerIdleClusters(FleetUpgradeRunEntity run, Map<String, ClusterAgentEntity> primaryByCluster) {
        long inFlight = primaryByCluster.values().stream()
                .filter(e -> e.getUpgradeStatus() == ClusterAgentUpgradeStatus.IN_PROGRESS
                        || e.getUpgradeStatus() == ClusterAgentUpgradeStatus.PENDING)
                .count();
        int slotsAvailable = run.getConcurrency() - (int) inFlight;
        if (slotsAvailable <= 0) {
            return;
        }
        List<ClusterAgentEntity> idle = new ArrayList<>();
        for (ClusterAgentEntity e : primaryByCluster.values()) {
            if (e.getUpgradeStatus() == ClusterAgentUpgradeStatus.IDLE
                    || e.getUpgradeStatus() == ClusterAgentUpgradeStatus.SUCCEEDED
                    || e.getUpgradeStatus() == ClusterAgentUpgradeStatus.FAILED) {
                // SUCCEEDED — 이미 target image 면 skip. 다른 image 면 (이전 run 의 result)
                // 재시도 가능. agentUpgradeService.upgradeCluster 가 same-image no-op 처리.
                if (!isAlreadyOnTarget(e, run.getTargetImage())) {
                    idle.add(e);
                }
            }
            if (idle.size() >= slotsAvailable) break;
        }
        for (ClusterAgentEntity target : idle) {
            try {
                var result = agentUpgradeService.upgradeCluster(target.getClusterName(), run.getTargetImage());
                if ("NO_OP".equals(result.status())) {
                    run.setSkippedCount(run.getSkippedCount() + 1);
                }
            } catch (CustomException ce) {
                log.warn(
                        "Fleet upgrade per-cluster trigger failed cluster={}: {}",
                        target.getClusterName(),
                        ce.getMessage());
                run.setFailedCount(run.getFailedCount() + 1);
            }
        }
    }

    /**
     * Step 5 — wave 종결 평가 + threshold 위반 시 ABORT + threshold OK 시 다음 wave / 마지막이면
     * COMPLETED. wave 진행 중이면 no-op (main 의 final save 가 trigger 변경분 commit).
     *
     * <p>ABORT path 만 자체 save (terminal). 다른 path 는 main 의 final save 가 처리.
     */
    private void advanceWaveOrFinishRun(
            FleetUpgradeRunEntity run,
            Map<String, ClusterAgentEntity> primaryByCluster,
            ClusterAgentUpgradeWave currentWave,
            List<ClusterAgentUpgradeWave> waves,
            LocalDateTime now) {
        int waveTotal = primaryByCluster.size();
        int waveSucceeded = 0;
        int waveFailed = 0;
        int wavePending = 0;
        for (ClusterAgentEntity e : primaryByCluster.values()) {
            switch (e.getUpgradeStatus()) {
                case SUCCEEDED -> {
                    if (isAlreadyOnTarget(e, run.getTargetImage())) waveSucceeded++;
                }
                case FAILED -> waveFailed++;
                case IN_PROGRESS, PENDING -> wavePending++;
                case IDLE -> {} // trigger 후 monitor 가 SUCCEEDED 로 갱신 전 state
            }
        }
        if (wavePending != 0 || waveTotal == 0) {
            return; // wave 아직 진행 중 또는 빈 wave.
        }

        // wave 끝. failure rate 검사.
        int finishedCount = waveSucceeded + waveFailed;
        int failurePct = finishedCount == 0 ? 0 : (int) Math.round((waveFailed * 100.0) / finishedCount);
        run.setSucceededCount(run.getSucceededCount() + waveSucceeded);

        if (failurePct >= run.getFailureThreshold()) {
            run.setStatus(FleetUpgradeRunStatus.ABORTED);
            run.setCompletedAt(now);
            run.setLastError("Wave " + currentWave + " failure rate " + failurePct + "% >= threshold "
                    + run.getFailureThreshold() + "%");
            runRepository.save(run); // ABORT 는 terminal — 즉시 commit.
            log.warn("Fleet upgrade ABORTED run_id={} wave={} failure_pct={}", run.getRunId(), currentWave, failurePct);
            return;
        }

        // 다음 wave 로 또는 COMPLETED.
        int idx = waves.indexOf(currentWave);
        if (idx + 1 < waves.size()) {
            ClusterAgentUpgradeWave next = waves.get(idx + 1);
            run.setCurrentWave(next.name());
            log.info(
                    "Fleet upgrade run_id={} wave {} → {} ({}succeeded {}failed)",
                    run.getRunId(),
                    currentWave,
                    next,
                    waveSucceeded,
                    waveFailed);
        } else {
            run.setStatus(FleetUpgradeRunStatus.COMPLETED);
            run.setCompletedAt(now);
            run.setCurrentWave(null);
            log.info(
                    "Fleet upgrade COMPLETED run_id={} (final wave={} succeeded={} failed={})",
                    run.getRunId(),
                    currentWave,
                    waveSucceeded,
                    waveFailed);
        }
    }

    private static boolean isAlreadyOnTarget(ClusterAgentEntity e, String targetImage) {
        if (e.getAgentVersion() == null || targetImage == null) {
            return false;
        }
        int colonIdx = targetImage.lastIndexOf(':');
        String tag =
                colonIdx > 0 && colonIdx < targetImage.length() - 1 ? targetImage.substring(colonIdx + 1) : targetImage;
        return tag.equals(e.getAgentVersion());
    }

    private static List<ClusterAgentUpgradeWave> parseWaves(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        List<ClusterAgentUpgradeWave> out = new ArrayList<>();
        for (String token : csv.split(",")) {
            String trimmed = token.trim();
            if (trimmed.isEmpty()) continue;
            try {
                out.add(ClusterAgentUpgradeWave.valueOf(trimmed));
            } catch (IllegalArgumentException e) {
                // silent drop 금지. unknown wave token 은 즉시
                // INVALID_INPUT_VALUE. controller validation 이 enum 검증을 이미 하므로 정상
                // input 엔 무영향. DB 의 stale waves_csv 가 corrupt 됐을 때만 fire.
                throw new com.aipaas.anycloud.common.error.exception.CustomException(
                        "Unknown upgrade wave token: '" + trimmed + "' (expected one of "
                                + Arrays.toString(ClusterAgentUpgradeWave.values()) + ")",
                        com.aipaas.anycloud.common.error.enums.ErrorCode.INVALID_INPUT_VALUE);
            }
        }
        return out;
    }
}
