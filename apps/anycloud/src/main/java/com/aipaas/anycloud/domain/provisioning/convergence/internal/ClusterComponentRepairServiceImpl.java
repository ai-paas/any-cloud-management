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
        // 성공 시에도 백오프 적용. 적용과 준비 완료는 별개라 다음 probe 전에 재투입하면 동일
        // 매니페스트를 5분마다 재적용.
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

    /** 지수 백오프. 상한 부재 시 재시도 간격이 일 단위가 되어 조정 정지와 구분 불가. */
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

        component.apply(vmCluster, () -> provisioningService.stackOutputs(vmCluster.getStackName(), true, Map.of()));
    }

    @Override
    public void repair(VmClusterEntity vmCluster, ComponentType type) {
        applyComponent(vmCluster, type);

        // 시도 횟수 초기화. 운영자의 원인 조치를 전제하므로 이전 백오프 미승계.
        repository.findByVmClusterIdAndComponentType(vmCluster.getId(), type).ifPresent(row -> {
            row.setAttempts(0);
            row.setNextAttemptAt(null);
            row.setLastAppliedAt(ZonedDateTime.now(clock));
            row.setLastError(null);
            // health 미갱신. 적용 성공과 준비 완료는 별개이며 판정은 다음 probe 담당.
            repository.save(row);
        });
        log.info("구성 요소 수동 재적용 cluster={} type={}", vmCluster.getClusterName(), type);
    }
}
