package com.aipaas.anycloud.domain.agent.upgrade.impl;

import com.aipaas.anycloud.domain.agent.FleetUpgradeRunRepository;
import com.aipaas.anycloud.domain.agent.upgrade.FleetUpgradeRun;
import com.aipaas.anycloud.domain.agent.upgrade.FleetUpgradeRunEntity;
import com.aipaas.anycloud.domain.agent.upgrade.FleetUpgradeRunQueryService;
import com.aipaas.anycloud.domain.agent.upgrade.mapper.FleetUpgradeRunMapper;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link FleetUpgradeRunQueryService} impl. repository 위임.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FleetUpgradeRunQueryServiceImpl implements FleetUpgradeRunQueryService {

    private final FleetUpgradeRunRepository repository;
    private final FleetUpgradeRunMapper fleetUpgradeRunMapper;

    @Override
    public List<FleetUpgradeRunEntity> listRecentRuns() {
        return repository.findTop20ByOrderByCreatedAtDesc();
    }

    @Override
    public List<FleetUpgradeRun> listRecentRunsDomain() {
        return repository.findTop20ByOrderByCreatedAtDesc().stream()
                .map(fleetUpgradeRunMapper::toDomain)
                .toList();
    }
}
