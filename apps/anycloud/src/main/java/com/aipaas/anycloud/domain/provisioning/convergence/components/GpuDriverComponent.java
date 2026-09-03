package com.aipaas.anycloud.domain.provisioning.convergence.components;

import com.aipaas.anycloud.domain.provisioning.VmClusterEntity;
import com.aipaas.anycloud.domain.provisioning.bootstrap.support.VmClusterNodeResolver;
import com.aipaas.anycloud.domain.provisioning.convergence.ClusterComponent;
import com.aipaas.anycloud.domain.provisioning.convergence.ComponentProbe;
import com.aipaas.anycloud.domain.provisioning.convergence.ComponentType;
import com.aipaas.anycloud.domain.provisioning.convergence.Requirement;
import com.aipaas.anycloud.domain.provisioning.model.VmClusterInternalRequestSnapshot;
import com.aipaas.anycloud.domain.provisioning.remote.VmClusterRemoteAccessService;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 노드의 NVIDIA 커널 드라이버 존재를 {@code nvidia-smi} 로 판정.
 *
 * <p>GPU instance 는 worker 로 배치되므로 worker 노드만 본다. 하나라도 장치를 보고하면 READY 다 —
 * 혼합 노드 풀에서 GPU 노드가 일부인 구성을 미충족으로 보고하면 안 된다.
 */
@Component
@RequiredArgsConstructor
public class GpuDriverComponent implements ClusterComponent {

    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(30);
    private static final String NVIDIA_SMI_LIST = "nvidia-smi -L";

    private final VmClusterRemoteAccessService remoteAccess;
    private final VmClusterNodeResolver nodeResolver;

    @Override
    public ComponentType type() {
        return ComponentType.GPU_DRIVER;
    }

    @Override
    public Requirement requirementFor(VmClusterInternalRequestSnapshot spec) {
        return Boolean.TRUE.equals(spec.getEnableGpuOperator())
                ? Requirement.REQUIRED
                : Requirement.NOT_APPLICABLE;
    }

    @Override
    public ComponentProbe probe(VmClusterEntity cluster, Map<String, Object> outputs) {
        List<String> workers = workerHosts(outputs);
        if (workers.isEmpty()) {
            return ComponentProbe.unknown("worker 노드를 outputs 에서 찾지 못했습니다");
        }
        boolean anyProbeSucceeded = false;
        String lastError = null;
        for (String host : workers) {
            try {
                String raw = remoteAccess.runOnHost(cluster, outputs, host, NVIDIA_SMI_LIST, PROBE_TIMEOUT);
                anyProbeSucceeded = true;
                if (hasGpuDevice(raw)) {
                    return ComponentProbe.ready();
                }
            } catch (Exception e) {
                lastError = host + ": " + e.getMessage();
            }
        }
        // 전부 transport 실패면 드라이버 부재를 단정할 수 없다.
        return anyProbeSucceeded
                ? ComponentProbe.notReady("어떤 worker 도 GPU 장치를 보고하지 않습니다")
                : ComponentProbe.unknown("모든 worker probe 실패: " + lastError);
    }

    List<String> workerHosts(Map<String, Object> outputs) {
        try {
            return nodeResolver.readNodes(outputs).stream()
                    .filter(node -> "worker".equalsIgnoreCase(node.role()))
                    .map(VmClusterNodeResolver.VmClusterNode::host)
                    .filter(host -> host != null && !host.isBlank())
                    .toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    /** {@code nvidia-smi -L} 은 장치가 없으면 "No devices were found" 를 낸다. */
    static boolean hasGpuDevice(String raw) {
        return raw != null && raw.contains("GPU 0:");
    }
}
