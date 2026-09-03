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

/** ingress-nginx controller 의 readyReplicas 로 설치 여부를 판정. */
@Component
@RequiredArgsConstructor
public class IngressComponent implements ClusterComponent {

    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(30);

    private static final String READY_REPLICAS_COMMAND =
            "sudo KUBECONFIG=/etc/kubernetes/admin.conf kubectl get deployment "
                    + "-n ingress-nginx ingress-nginx-controller "
                    + "-o jsonpath='{.status.readyReplicas}' 2>/dev/null";

    private final VmClusterRemoteAccessService remoteAccess;

    @Override
    public ComponentType type() {
        return ComponentType.INGRESS;
    }

    @Override
    public Requirement requirementFor(VmClusterInternalRequestSnapshot spec) {
        return Boolean.TRUE.equals(spec.getEnableIngress()) ? Requirement.REQUIRED : Requirement.NOT_APPLICABLE;
    }

    @Override
    public ComponentProbe probe(VmClusterEntity cluster, Map<String, Object> outputs) {
        String raw;
        try {
            raw = remoteAccess.runOnMaster(cluster, outputs, READY_REPLICAS_COMMAND, PROBE_TIMEOUT);
        } catch (Exception e) {
            return ComponentProbe.unknown("ingress controller 조회 실패: " + e.getMessage());
        }
        return isDeploymentAvailable(raw)
                ? ComponentProbe.ready()
                : ComponentProbe.notReady("ingress-nginx-controller 에 ready replica 가 없습니다");
    }

    /** deployment 가 없으면 jsonpath 결과가 빈 문자열이다. */
    static boolean isDeploymentAvailable(String raw) {
        if (raw == null || raw.isBlank()) {
            return false;
        }
        try {
            return Integer.parseInt(raw.trim()) > 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
