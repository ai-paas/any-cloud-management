package com.aipaas.anycloud.domain.agent;

import com.aipaas.anycloud.domain.agent.upgrade.FleetUpgradeRunEntity;
import com.aipaas.anycloud.domain.agent.upgrade.FleetUpgradeRunStatus;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface FleetUpgradeRunRepository extends JpaRepository<FleetUpgradeRunEntity, String> {

    /** Scheduler 가 active (PLANNED / RUNNING) run 을 sweep 할 때 사용. */
    List<FleetUpgradeRunEntity> findByStatusIn(List<FleetUpgradeRunStatus> statuses);

    /** REST 가 운영자에게 최근 run history 보여줄 때. createdAt DESC 정렬은 caller 가 처리. */
    List<FleetUpgradeRunEntity> findTop20ByOrderByCreatedAtDesc();
}
