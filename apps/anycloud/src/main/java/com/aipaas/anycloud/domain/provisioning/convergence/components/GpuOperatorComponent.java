package com.aipaas.anycloud.domain.provisioning.convergence.components;

import com.aipaas.anycloud.domain.provisioning.VmClusterEntity;
import com.aipaas.anycloud.domain.provisioning.convergence.ClusterComponent;
import com.aipaas.anycloud.domain.provisioning.convergence.ComponentProbe;
import com.aipaas.anycloud.domain.provisioning.convergence.ComponentType;
import com.aipaas.anycloud.domain.provisioning.convergence.Requirement;
import com.aipaas.anycloud.domain.provisioning.model.VmClusterInternalRequestSnapshot;
import com.aipaas.anycloud.domain.provisioning.remote.VmClusterRemoteAccessService;
import java.time.Duration;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * NVIDIA GPU operator 설치 여부를 노드의 allocatable 자원으로 판정.
 *
 * <p>helm release 존재가 아니라 allocatable 을 보는 이유 — release 는 있는데 driver DaemonSet 이
 * CrashLoop 이면 GPU 를 쓸 수 없고, 그 상태를 설치됨으로 보고하면 안 된다.
 */
@Component
@RequiredArgsConstructor
public class GpuOperatorComponent implements ClusterComponent {

    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration APPLY_TIMEOUT = Duration.ofMinutes(5);

    /** jsonpath 의 키 이름에 점이 있어 escape 가 필요하다. */
    private static final String ALLOCATABLE_GPU_COMMAND =
            "sudo KUBECONFIG=/etc/kubernetes/admin.conf kubectl get nodes "
                    + "-o jsonpath='{.items[*].status.allocatable.nvidia\\.com/gpu}'";

    private final VmClusterRemoteAccessService remoteAccess;

    @Override
    public ComponentType type() {
        return ComponentType.GPU_OPERATOR;
    }

    @Override
    public Requirement requirementFor(VmClusterInternalRequestSnapshot spec) {
        return Boolean.TRUE.equals(spec.getEnableGpuOperator())
                ? Requirement.REQUIRED
                : Requirement.NOT_APPLICABLE;
    }

    @Override
    public ComponentProbe probe(VmClusterEntity cluster, Map<String, Object> outputs) {
        String raw;
        try {
            raw = remoteAccess.runOnMaster(cluster, outputs, ALLOCATABLE_GPU_COMMAND, PROBE_TIMEOUT);
        } catch (Exception e) {
            return ComponentProbe.unknown("allocatable GPU 조회 실패: " + e.getMessage());
        }
        return totalAllocatableGpu(raw) > 0
                ? ComponentProbe.ready()
                : ComponentProbe.notReady("no node reports allocatable nvidia.com/gpu");
    }

    /**
     * helm chart 설치만 하고 준비 대기는 하지 않는다.
     *
     * <p>{@code driver.enabled=true} 로 operator 가 컨테이너 드라이버를 관리한다. 호스트에 드라이버를
     * 따로 깔면 driver 파드의 init 컨테이너가 이를 감지해 파드를 종료시키며, NVIDIA 가 금지하는 조합이다.
     *
     * <p>기존 셸 경로에는 {@code kubectl wait --timeout=15m} 이 있었는데, 이 코드는 RabbitMQ
     * consumer 스레드에서 돌아 consumer 하나를 15분 묶었다. 준비 확인은 probe 가 맡는다.
     */
    @Override
    public void apply(VmClusterEntity cluster, Map<String, Object> outputs) {
        String script = String.join(
                " && ",
                "command -v helm >/dev/null 2>&1 || "
                        + "curl -fsSL https://raw.githubusercontent.com/helm/helm/main/scripts/get-helm-3 | bash",
                "sudo KUBECONFIG=/etc/kubernetes/admin.conf kubectl get namespace gpu-operator >/dev/null 2>&1 || "
                        + "sudo KUBECONFIG=/etc/kubernetes/admin.conf kubectl create namespace gpu-operator",
                "helm repo add nvidia https://helm.ngc.nvidia.com/nvidia >/dev/null 2>&1; "
                        + "helm repo update >/dev/null 2>&1",
                "sudo KUBECONFIG=/etc/kubernetes/admin.conf helm upgrade --install gpu-operator "
                        + "nvidia/gpu-operator --namespace gpu-operator "
                        + "--set driver.enabled=true --set toolkit.enabled=true "
                        + "--set dcgmExporter.serviceMonitor.enabled=true");
        remoteAccess.runOnMaster(cluster, outputs, script, APPLY_TIMEOUT);
    }

    /** GPU 없는 노드는 키가 없어 빈 토큰으로 나온다. 숫자가 아닌 토큰은 0 으로 센다. */
    static int totalAllocatableGpu(String raw) {
        if (raw == null || raw.isBlank()) {
            return 0;
        }
        int total = 0;
        for (String token : raw.trim().split("\\s+")) {
            try {
                total += Integer.parseInt(token);
            } catch (NumberFormatException ignored) {
                // 빈 토큰, <none> 등 — GPU 미보고 노드
            }
        }
        return total;
    }
}
