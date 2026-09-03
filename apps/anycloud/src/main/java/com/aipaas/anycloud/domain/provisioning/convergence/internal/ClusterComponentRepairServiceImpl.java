package com.aipaas.anycloud.domain.provisioning.convergence.internal;

import com.aipaas.anycloud.domain.provisioning.VmClusterEntity;
import com.aipaas.anycloud.domain.provisioning.convergence.ClusterComponent;
import com.aipaas.anycloud.domain.provisioning.convergence.ClusterComponentRepairService;
import com.aipaas.anycloud.domain.provisioning.convergence.ComponentType;
import com.aipaas.anycloud.domain.provisioning.convergence.VmClusterComponentRepository;
import io.aipaas.cluster.provisioning.api.ProvisioningService;
import java.time.Clock;
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
    public void repair(VmClusterEntity vmCluster, ComponentType type) {
        ClusterComponent component = components.stream()
                .filter(c -> c.type() == type)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("구현이 없는 구성 요소: " + type));

        Map<String, Object> outputs = provisioningService.stackOutputs(vmCluster.getStackName(), true, Map.of());
        component.apply(vmCluster, outputs);

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
