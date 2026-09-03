package com.aipaas.anycloud.domain.provisioning.scale.internal;

import com.aipaas.anycloud.domain.provisioning.VmClusterEntity;
import com.aipaas.anycloud.domain.provisioning.remote.VmClusterRemoteAccessService;
import com.aipaas.anycloud.domain.provisioning.scale.VmClusterNodeLabelService;
import com.aipaas.anycloud.domain.provisioning.scale.VmClusterScaleDrainService;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class VmClusterScaleDrainServiceImpl implements VmClusterScaleDrainService {

    private static final Duration KUBECTL_TIMEOUT = Duration.ofMinutes(2);
    private static final Duration DRAIN_TIMEOUT = Duration.ofMinutes(7);
    private static final String KUBECONFIG = "/etc/kubernetes/admin.conf";

    private final VmClusterRemoteAccessService remoteAccessService;
    private final VmClusterNodeLabelService nodeLabelService;

    @Override
    public List<String> drainExcessWorkers(VmClusterEntity vmCluster, Map<String, Object> outputs, int removeCount) {
        if (removeCount <= 0) {
            return List.of();
        }

        // 1) Pulumi index → K8s node 라벨을 먼저 reconcile (idempotent).
        //    이후 outputs.nodes[].role 의 worker-N 인덱스로 정확히 어느 K8s 노드가
        //    Pulumi 가 지울 워커인지 결정가능.
        Map<String, String> labelByNode = nodeLabelService.reconcilePulumiIndexLabels(vmCluster, outputs);

        List<String> targets = selectTargetsByPulumiIndex(labelByNode, removeCount);
        if (!targets.isEmpty()) {
            log.info(
                    "Scale drain: selected {} target(s) by pulumi-index label on cluster {}: {}",
                    targets.size(),
                    vmCluster.getClusterName(),
                    targets);
        } else {
            // 2) Fallback — 라벨이 없거나 매칭 실패 시 creationTimestamp 순. 기존 동작 보존.
            List<String> workerNodes = listWorkerNodesSortedByCreation(vmCluster, outputs);
            if (workerNodes.isEmpty()) {
                log.warn("Scale drain: no worker nodes resolved for cluster {}", vmCluster.getClusterName());
                return List.of();
            }
            int from = Math.max(0, workerNodes.size() - removeCount);
            targets = workerNodes.subList(from, workerNodes.size());
            log.info(
                    "Scale drain: fallback by creationTimestamp on cluster {} → {}",
                    vmCluster.getClusterName(),
                    targets);
        }
        List<String> attempted = new ArrayList<>(targets.size());

        for (String node : targets) {
            attempted.add(node);
            try {
                String cordon = String.format("sudo kubectl --kubeconfig %s cordon %s", KUBECONFIG, node);
                remoteAccessService.runOnMaster(vmCluster, outputs, cordon, KUBECTL_TIMEOUT);

                String drain = String.format(
                        "sudo kubectl --kubeconfig %s drain %s "
                                + "--ignore-daemonsets --delete-emptydir-data "
                                + "--force --timeout=%ds",
                        KUBECONFIG, node, DRAIN_TIMEOUT.toSeconds());
                remoteAccessService.runOnMaster(vmCluster, outputs, drain, DRAIN_TIMEOUT.plusMinutes(1));
                log.info("Drained worker node {} on cluster {}", node, vmCluster.getClusterName());
            } catch (Exception e) {
                // best-effort: drain 실패해도 Pulumi up 은 진행. 운영자가 사전 drain 한 케이스 대비.
                log.warn(
                        "Failed to drain node {} on cluster {}: {} — proceeding with Pulumi anyway",
                        node,
                        vmCluster.getClusterName(),
                        e.toString());
            }
        }
        return attempted;
    }

    /**
     * 라벨 {@code anycloud.aipaas/pulumi-index=worker-N} 기준으로 상위 N 인덱스를 골라
     * drain 대상으로 반환. Pulumi 는 workerCount 감소 시 배열 끝부터 잘라내므로
     * 인덱스가 큰 순서대로 사라진다 → 동일한 순서로 K8s 측에서 미리 비운다.
     *
     * @return 대상 노드 이름 리스트 (인덱스 큰 순서대로). 라벨이 충분치 않으면 빈 리스트.
     */
    private List<String> selectTargetsByPulumiIndex(Map<String, String> labelByNode, int removeCount) {
        if (labelByNode == null || labelByNode.isEmpty()) {
            return List.of();
        }
        // node → 인덱스(int) 매핑 후 인덱스 내림차순.
        record NodeIdx(String node, int index) {}
        List<NodeIdx> ordered = labelByNode.entrySet().stream()
                .map(e -> {
                    Integer idx = parseWorkerIndex(e.getValue());
                    return idx == null ? null : new NodeIdx(e.getKey(), idx);
                })
                .filter(java.util.Objects::nonNull)
                .sorted(Comparator.comparingInt(NodeIdx::index).reversed())
                .toList();
        if (ordered.size() < removeCount) {
            // 라벨이 일부만 부착된 경우 부분 신뢰. Pulumi 가 지우려는 정확한 인덱스가
            // 라벨에 없을 수 있으므로 fallback 로 위임.
            return List.of();
        }
        return ordered.stream().limit(removeCount).map(NodeIdx::node).toList();
    }

    private static Integer parseWorkerIndex(String labelValue) {
        if (labelValue == null) return null;
        // 형식 "worker-N"
        int dash = labelValue.lastIndexOf('-');
        if (dash < 0 || dash == labelValue.length() - 1) return null;
        try {
            return Integer.parseInt(labelValue.substring(dash + 1));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * master 에서 kubectl 로 worker 노드 이름을 creationTimestamp 오름차순으로 가져온다.
     * control-plane 노드는 제외.
     */
    private List<String> listWorkerNodesSortedByCreation(VmClusterEntity vmCluster, Map<String, Object> outputs) {
        try {
            String cmd = String.format(
                    "sudo kubectl --kubeconfig %s get nodes "
                            + "-l '!node-role.kubernetes.io/control-plane' "
                            + "--sort-by=.metadata.creationTimestamp "
                            + "-o jsonpath='{range .items[*]}{.metadata.name}{\"\\n\"}{end}'",
                    KUBECONFIG);
            String output = remoteAccessService.runOnMaster(vmCluster, outputs, cmd, KUBECTL_TIMEOUT);
            if (output == null || output.isBlank()) {
                return List.of();
            }
            return Arrays.stream(output.split("\\R"))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toList();
        } catch (Exception e) {
            log.warn("Failed to list worker nodes on cluster {}: {}", vmCluster.getClusterName(), e.toString());
            return List.of();
        }
    }
}
