package com.aipaas.anycloud.domain.agent.upgrade;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipaas.anycloud.domain.agent.upgrade.mapper.FleetUpgradeRunMapper;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

/**
 * FleetUpgradeRunMapper 단위 테스트 — MapStruct instance ({@link Mappers#getMapper}) 호출
 * round-trip 검증.
 */
class FleetUpgradeRunMapperTest {

    private final FleetUpgradeRunMapper mapper = Mappers.getMapper(FleetUpgradeRunMapper.class);

    @Test
    void toDomain_nullEntity_returnsNull() {
        assertThat(mapper.toDomain(null)).isNull();
    }

    @Test
    void toEntity_nullDomain_returnsNull() {
        assertThat(mapper.toEntity(null)).isNull();
    }

    @Test
    void toDomain_fullEntity_mapsAllFields() {
        FleetUpgradeRunEntity e = FleetUpgradeRunEntity.builder()
                .runId("run-1")
                .targetImage("ghcr.io/ai-paas/cluster-agent:v0.2.0")
                .wavesCsv("CANARY,STAGING,GENERAL")
                .currentWave("STAGING")
                .status(FleetUpgradeRunStatus.RUNNING)
                .concurrency(5)
                .failureThreshold(20)
                .totalClusters(100)
                .succeededCount(30)
                .failedCount(2)
                .skippedCount(3)
                .createdBy("alice")
                .build();

        FleetUpgradeRun d = mapper.toDomain(e);

        assertThat(d.runId()).isEqualTo("run-1");
        assertThat(d.targetImage()).isEqualTo("ghcr.io/ai-paas/cluster-agent:v0.2.0");
        assertThat(d.status()).isEqualTo(FleetUpgradeRunStatus.RUNNING);
        assertThat(d.totalClusters()).isEqualTo(100);
        assertThat(d.succeededCount()).isEqualTo(30);
        assertThat(d.createdBy()).isEqualTo("alice");
    }

    @Test
    void isTerminal_completedOrAborted_true() {
        FleetUpgradeRun completed = newRun(FleetUpgradeRunStatus.COMPLETED, 100, 100, 0, 0);
        FleetUpgradeRun aborted = newRun(FleetUpgradeRunStatus.ABORTED, 100, 50, 30, 5);
        FleetUpgradeRun running = newRun(FleetUpgradeRunStatus.RUNNING, 100, 10, 0, 0);

        assertThat(completed.isTerminal()).isTrue();
        assertThat(aborted.isTerminal()).isTrue();
        assertThat(running.isTerminal()).isFalse();
    }

    @Test
    void progressPercent_computesFromCounts() {
        FleetUpgradeRun zeroTotal = newRun(FleetUpgradeRunStatus.PLANNED, 0, 0, 0, 0);
        FleetUpgradeRun running = newRun(FleetUpgradeRunStatus.RUNNING, 100, 30, 5, 5);
        FleetUpgradeRun fullDone = newRun(FleetUpgradeRunStatus.COMPLETED, 100, 100, 0, 0);

        assertThat(zeroTotal.progressPercent()).isEqualTo(0);
        assertThat(running.progressPercent()).isEqualTo(40);
        assertThat(fullDone.progressPercent()).isEqualTo(100);
    }

    private FleetUpgradeRun newRun(FleetUpgradeRunStatus status, int total, int ok, int fail, int skip) {
        return new FleetUpgradeRun(
                "id", "img", "wave", null, status, 5, 20, total, ok, fail, skip, "u", null, null, null, null);
    }
}
