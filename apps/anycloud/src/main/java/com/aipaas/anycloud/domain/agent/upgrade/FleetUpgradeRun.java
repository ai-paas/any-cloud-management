package com.aipaas.anycloud.domain.agent.upgrade;

import java.time.LocalDateTime;

/**
 * Fleet upgrade run 의 immutable 도메인 표현.
 *
 * <p>JPA 와 분리된 record. 자세한 lifecycle 은 {@link com.aipaas.anycloud.domain.agent.upgrade.FleetUpgradeRunEntity}
 * 의 javadoc 을 참조합니다. 변환은
 * {@link com.aipaas.anycloud.domain.agent.upgrade.mapper.FleetUpgradeRunMapper}.
 *
 * @param runId            UUID.
 * @param targetImage      배포 대상 image (ghcr.io/... 형식).
 * @param wavesCsv         처리할 wave CSV (예: "CANARY,STAGING,GENERAL").
 * @param currentWave      RUNNING 일 때의 진행 wave. PLANNED/COMPLETED/ABORTED 면 null 가능.
 * @param status           PLANNED → RUNNING → COMPLETED / ABORTED.
 * @param concurrency      동시 업그레이드할 cluster 수.
 * @param failureThreshold 누적 실패 cluster 비율 (%). 도달 시 ABORTED.
 * @param totalClusters    대상 cluster 총수.
 * @param succeededCount   업그레이드 성공 cluster 수.
 * @param failedCount      업그레이드 실패 cluster 수.
 * @param skippedCount     skip cluster 수 (PAUSED wave 등).
 * @param createdBy        실행 유저 (principal).
 * @param createdAt        row 생성 시각.
 * @param startedAt        RUNNING 진입 시각.
 * @param completedAt      terminal state 진입 시각.
 * @param lastError        ABORTED 시 사유.
 */
public record FleetUpgradeRun(
        String runId,
        String targetImage,
        String wavesCsv,
        String currentWave,
        FleetUpgradeRunStatus status,
        int concurrency,
        int failureThreshold,
        int totalClusters,
        int succeededCount,
        int failedCount,
        int skippedCount,
        String createdBy,
        LocalDateTime createdAt,
        LocalDateTime startedAt,
        LocalDateTime completedAt,
        String lastError) {

    /** 더 이상 전환 없는 terminal status 여부. */
    public boolean isTerminal() {
        return status == FleetUpgradeRunStatus.COMPLETED || status == FleetUpgradeRunStatus.ABORTED;
    }

    /** 0..100 진행률 — totalClusters 0 이면 0. */
    public int progressPercent() {
        if (totalClusters <= 0) {
            return 0;
        }
        int processed = succeededCount + failedCount + skippedCount;
        return Math.min(100, (int) Math.round(processed * 100.0 / totalClusters));
    }
}
