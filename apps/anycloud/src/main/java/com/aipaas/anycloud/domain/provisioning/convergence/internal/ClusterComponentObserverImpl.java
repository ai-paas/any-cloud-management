package com.aipaas.anycloud.domain.provisioning.convergence.internal;

import com.aipaas.anycloud.domain.provisioning.VmClusterEntity;
import com.aipaas.anycloud.domain.provisioning.bootstrap.support.VmClusterBootstrapSnapshotService;
import com.aipaas.anycloud.domain.provisioning.convergence.ClusterComponent;
import com.aipaas.anycloud.domain.provisioning.convergence.ClusterComponentObserver;
import com.aipaas.anycloud.domain.provisioning.convergence.ComponentHealth;
import com.aipaas.anycloud.domain.provisioning.convergence.ComponentObservation;
import com.aipaas.anycloud.domain.provisioning.convergence.ComponentProbe;
import com.aipaas.anycloud.domain.provisioning.convergence.Requirement;
import com.aipaas.anycloud.domain.provisioning.convergence.VmClusterComponentEntity;
import com.aipaas.anycloud.domain.provisioning.convergence.VmClusterComponentRepository;
import com.aipaas.anycloud.domain.provisioning.model.VmClusterInternalRequestSnapshot;
import io.aipaas.cluster.provisioning.api.ProvisioningService;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class ClusterComponentObserverImpl implements ClusterComponentObserver {

    private final List<ClusterComponent> components;
    private final VmClusterComponentRepository repository;
    private final VmClusterBootstrapSnapshotService snapshotService;
    private final ProvisioningService provisioningService;

    @Override
    public List<ComponentObservation> observe(VmClusterEntity vmCluster) {
        // 값을 얻으려면 Pulumi 를 호출해야 한다. 쓰는 컴포넌트가 있을 때만 한 번 부르고 재사용한다.
        Supplier<Map<String, Object>> outputs =
                memoize(() -> provisioningService.stackOutputs(vmCluster.getStackName(), true, Map.of()));
        VmClusterInternalRequestSnapshot spec = snapshotService.read(vmCluster.getRequestConfig());

        List<ComponentObservation> observations = new ArrayList<>();
        for (ClusterComponent component : components) {
            Requirement requirement = component.requirementFor(spec);
            if (requirement == Requirement.NOT_APPLICABLE) {
                continue;
            }
            ComponentProbe probe = safeProbe(component, vmCluster, outputs);
            persist(vmCluster, component, requirement, probe);
            observations.add(new ComponentObservation(component.type(), requirement, probe.health(), probe.detail()));
        }
        return observations;
    }

    @Override
    public List<ComponentObservation> currentComponents(String vmClusterId) {
        return repository.findByVmClusterId(vmClusterId).stream()
                .map(row -> new ComponentObservation(
                        row.getComponentType(), row.getRequirement(), row.getHealth(), row.getLastError()))
                .toList();
    }

    /** 한 번만 계산하고 재사용한다. 같은 관측 안에서 컴포넌트가 여럿이어도 Pulumi 는 한 번만 부른다. */
    private static <T> Supplier<T> memoize(Supplier<T> delegate) {
        return new Supplier<>() {
            private boolean resolved;
            private T value;

            @Override
            public T get() {
                if (!resolved) {
                    value = delegate.get();
                    resolved = true;
                }
                return value;
            }
        };
    }

    /** 계약상 probe 는 예외를 던지지 않지만, 구현 실수가 나머지 컴포넌트 관측을 막으면 안 된다. */
    private ComponentProbe safeProbe(
            ClusterComponent component, VmClusterEntity vmCluster, Supplier<Map<String, Object>> outputs) {
        try {
            return component.probe(vmCluster, outputs);
        } catch (Exception e) {
            log.warn(
                    "probe 가 예외를 던짐 type={} cluster={}: {}",
                    component.type(),
                    vmCluster.getClusterName(),
                    e.toString());
            return ComponentProbe.unknown("probe 예외: " + e.getMessage());
        }
    }

    private void persist(
            VmClusterEntity vmCluster, ClusterComponent component, Requirement requirement, ComponentProbe probe) {
        VmClusterComponentEntity row = repository
                .findByVmClusterIdAndComponentType(vmCluster.getId(), component.type())
                .orElseGet(() -> {
                    VmClusterComponentEntity created = new VmClusterComponentEntity();
                    created.setVmClusterId(vmCluster.getId());
                    created.setComponentType(component.type());
                    return created;
                });
        row.setRequirement(requirement);
        row.setHealth(probe.health());
        row.setLastProbedAt(ZonedDateTime.now());
        row.setLastError(probe.health() == ComponentHealth.READY ? null : probe.detail());
        repository.save(row);
    }
}
