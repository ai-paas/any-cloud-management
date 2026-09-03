package com.aipaas.anycloud.domain.agent.upgrade;

import java.util.List;

/**
 * Fleet upgrade run 이력 조회 (read-only).
 *
 * <p>Impl: {@link com.aipaas.anycloud.domain.agent.upgrade.impl.FleetUpgradeRunQueryServiceImpl}.
 *
 * <p>{@code *Entity} 메서드와 {@code *Domain*} 메서드가 양립합니다.
 * 새 caller 는 domain 변형 사용 (immutable record). entity 변형은 점진 deprecate.
 */
public interface FleetUpgradeRunQueryService {

    /** 최근 20개 run (PLANNED / RUNNING / COMPLETED / ABORTED 모두). createdAt DESC. */
    List<FleetUpgradeRunEntity> listRecentRuns();

    /** {@link #listRecentRuns()} 의 domain 변형. */
    List<FleetUpgradeRun> listRecentRunsDomain();
}
