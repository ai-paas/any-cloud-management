package com.aipaas.anycloud.domain.provisioning.convergence.internal;

import com.aipaas.anycloud.domain.provisioning.VmClusterEntity;
import com.aipaas.anycloud.domain.provisioning.convergence.ClusterComponent;
import com.aipaas.anycloud.domain.provisioning.convergence.ClusterComponentRepairService;
import com.aipaas.anycloud.domain.provisioning.convergence.ComponentType;
import com.aipaas.anycloud.domain.provisioning.convergence.VmClusterComponentRepository;
import io.aipaas.cluster.provisioning.api.ProvisioningService;
import java.time.Clock;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class ClusterComponentRepairServiceImpl implements ClusterComponentRepairService {

    private final List<ClusterComponent> components;
    private final VmClusterComponentRepository repository;
    private final ProvisioningService provisioningService;
    private final Clock clock;

    @Override
    public boolean repairIfDue(VmClusterEntity vmCluster, ComponentType type) {
        var row = repository
                .findByVmClusterIdAndComponentType(vmCluster.getId(), type)
                .orElse(null);
        if (row == null) {
            return false;
        }
        ZonedDateTime now = ZonedDateTime.now(clock);
        if (row.getNextAttemptAt() != null && now.isBefore(row.getNextAttemptAt())) {
            return false;
        }

        int attempt = (row.getAttempts() == null ? 0 : row.getAttempts()) + 1;
        row.setAttempts(attempt);
        // 성공해도 백오프를 건다 — 적용이 곧 준비 완료가 아니라서 다음 probe 전에 다시 밀어 넣으면
        // 같은 매니페스트를 5분마다 재적용하게 된다.
        row.setNextAttemptAt(now.plus(backoff(attempt)));
        try {
            applyComponent(vmCluster, type);
            row.setLastAppliedAt(now);
            row.setLastError(null);
            log.info("구성 요소 자동 재적용 cluster={} type={} attempt={}", vmCluster.getClusterName(), type, attempt);
        } catch (Exception e) {
            row.setLastError(e.toString());
            log.warn(
                    "구성 요소 자동 재적용 실패 cluster={} type={} attempt={}: {}",
                    vmCluster.getClusterName(),
                    type,
                    attempt,
                    e.toString());
        }
        repository.save(row);
        return true;
    }

    /** 지수 백오프. 상한을 두지 않으면 오래된 클러스터가 사실상 조정에서 빠진다. */
    private Duration backoff(int attempt) {
        long minutes = 1L << Math.min(attempt - 1, BACKOFF_MAX_SHIFT);
        return Duration.ofMinutes(Math.min(minutes, BACKOFF_CAP.toMinutes()));
    }

    private static final int BACKOFF_MAX_SHIFT = 6;
    private static final Duration BACKOFF_CAP = Duration.ofMinutes(30);

    private void applyComponent(VmClusterEntity vmCluster, ComponentType type) {
        ClusterComponent component = components.stream()
                .filter(c -> c.type() == type)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("구현이 없는 구성 요소: " + type));

        Map<String, Object> outputs = provisioningService.stackOutputs(vmCluster.getStackName(), true, Map.of());
        component.apply(vmCluster, outputs);
    }

    @Override
    public void repair(VmClusterEntity vmCluster, ComponentType type) {
        applyComponent(vmCluster, type);

        // 시도 회계를 초기화한다 — 운영자가 원인을 고쳤다는 전제이므로 이전 백오프를 끌고 가지 않는다.
        repository.findByVmClusterIdAndComponentType(vmCluster.getId(), type).ifPresent(row -> {
            row.setAttempts(0);
            row.setNextAttemptAt(null);
            row.setLastAppliedAt(ZonedDateTime.now(clock));
            row.setLastError(null);
            // health 는 갱신하지 않는다. 적용 성공이 준비 완료는 아니며 다음 probe 가 판정한다.
            repository.save(row);
        });
        log.info("구성 요소 수동 재적용 cluster={} type={}", vmCluster.getClusterName(), type);
    }
}
