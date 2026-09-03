package com.aipaas.anycloud.domain.provisioning.scale.internal;

import com.aipaas.anycloud.domain.provisioning.VmClusterEntity;
import com.aipaas.anycloud.domain.provisioning.remote.VmClusterRemoteAccessService;
import com.aipaas.anycloud.domain.provisioning.scale.VmClusterNodeLabelService;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class VmClusterNodeLabelServiceImpl implements VmClusterNodeLabelService {

    private static final Duration KUBECTL_TIMEOUT = Duration.ofMinutes(1);
    private static final String KUBECONFIG = "/etc/kubernetes/admin.conf";
    /** {@code role} 값 형식 {@code "worker-N"} 검증. master 는 라벨 대상 외. */
    private static final Pattern WORKER_ROLE = Pattern.compile("^worker-\\d+$");

    private final VmClusterRemoteAccessService remoteAccessService;

    @Override
    public Map<String, String> reconcilePulumiIndexLabels(VmClusterEntity vmCluster, Map<String, Object> outputs) {
        Map<String, String> result = new LinkedHashMap<>();
        List<Map<String, Object>> workers = extractWorkerNodes(outputs);
        if (workers.isEmpty()) {
            return result;
        }

        Map<String, String> ipToK8sNode = fetchK8sNodeIpMap(vmCluster, outputs);
        if (ipToK8sNode.isEmpty()) {
            log.warn("Label reconcile: empty K8s node map for cluster {} — skip", vmCluster.getClusterName());
            return result;
        }

        for (Map<String, Object> w : workers) {
            String role = stringOrNull(w.get("role"));
            String privateIp = stringOrNull(w.get("privateIp"));
            if (role == null || !WORKER_ROLE.matcher(role).matches() || privateIp == null || privateIp.isBlank()) {
                continue;
            }
            String k8sNode = ipToK8sNode.get(privateIp);
            if (k8sNode == null) {
                log.warn(
                        "Label reconcile: no K8s node found for pulumi {} privateIp={} on cluster {}",
                        role,
                        privateIp,
                        vmCluster.getClusterName());
                continue;
            }
            if (applyLabel(vmCluster, outputs, k8sNode, role)) {
                result.put(k8sNode, role);
            }
        }
        log.info(
                "Label reconcile: labeled {}/{} workers on cluster {}",
                result.size(),
                workers.size(),
                vmCluster.getClusterName());
        return result;
    }

    private boolean applyLabel(
            VmClusterEntity vmCluster, Map<String, Object> outputs, String nodeName, String roleValue) {
        try {
            // --overwrite 로 idempotent. role 값을 그대로 라벨 값으로 사용.
            String cmd = String.format(
                    "sudo kubectl --kubeconfig %s label node %s %s=%s --overwrite",
                    KUBECONFIG, shellWord(nodeName), PULUMI_INDEX_LABEL, shellWord(roleValue));
            remoteAccessService.runOnMaster(vmCluster, outputs, cmd, KUBECTL_TIMEOUT);
            return true;
        } catch (Exception e) {
            log.warn(
                    "Label reconcile: failed to label node {} with {}={} on cluster {}: {}",
                    nodeName,
                    PULUMI_INDEX_LABEL,
                    roleValue,
                    vmCluster.getClusterName(),
                    e.toString());
            return false;
        }
    }

    /**
     * master 에서 {@code kubectl get nodes} 로 InternalIP→nodeName 맵을 만든다.
     */
    private Map<String, String> fetchK8sNodeIpMap(VmClusterEntity vmCluster, Map<String, Object> outputs) {
        Map<String, String> map = new HashMap<>();
        try {
            String cmd = String.format(
                    "sudo kubectl --kubeconfig %s get nodes "
                            + "-o jsonpath='{range .items[*]}{.metadata.name}\\t"
                            + "{.status.addresses[?(@.type==\"InternalIP\")].address}{\"\\n\"}{end}'",
                    KUBECONFIG);
            String output = remoteAccessService.runOnMaster(vmCluster, outputs, cmd, KUBECTL_TIMEOUT);
            if (output == null || output.isBlank()) {
                return map;
            }
            for (String line : output.split("\\R")) {
                String[] parts = line.split("\\t", 2);
                if (parts.length < 2) continue;
                String name = parts[0].trim();
                String ip = parts[1].trim();
                if (!name.isEmpty() && !ip.isEmpty()) {
                    map.put(ip, name);
                }
            }
        } catch (Exception e) {
            log.warn(
                    "Label reconcile: kubectl get nodes failed on cluster {}: {}",
                    vmCluster.getClusterName(),
                    e.toString());
        }
        return map;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractWorkerNodes(Map<String, Object> outputs) {
        Object nodes = outputs == null ? null : outputs.get("nodes");
        if (!(nodes instanceof List<?> raw)) {
            return List.of();
        }
        return raw.stream()
                .filter(item -> item instanceof Map<?, ?>)
                .map(item -> (Map<String, Object>) item)
                .filter(m -> {
                    Object role = m.get("role");
                    return role != null
                            && WORKER_ROLE.matcher(String.valueOf(role)).matches();
                })
                .toList();
    }

    private static String stringOrNull(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    /**
     * shell metachar 회피용 보수적 quoting. node 이름/라벨 값은 영숫자/`.`/`-` 만 허용한다고
     * 가정하지만, 방어적으로 single-quote wrap + 내부 single-quote 분리. 더 일반화된 quoting 은
     * {@link com.aipaas.anycloud.domain.provisioning.bootstrap.providers.GenericLinuxVmClusterBootstrapStrategy}
     * 와 동일 패턴.
     */
    private static String shellWord(String v) {
        if (v == null) return "''";
        return "'" + v.replace("'", "'\\''") + "'";
    }
}
