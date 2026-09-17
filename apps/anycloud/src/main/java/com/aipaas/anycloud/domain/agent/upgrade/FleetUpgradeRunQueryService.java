package com.aipaas.anycloud.domain.agent.upgrade;

import java.util.List;

/** Fleet upgrade run 이력 조회 (read-only). */
public interface FleetUpgradeRunQueryService {

    /** 최근 20개 run (PLANNED / RUNNING / COMPLETED / ABORTED 모두). createdAt DESC. */
    List<FleetUpgradeRunEntity> listRecentRuns();

    /** {@link #listRecentRuns()} 의 domain 변형. */
    List<FleetUpgradeRun> listRecentRunsDomain();
}
