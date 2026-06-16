package com.aipaas.anycloud.domain.cluster.admin.impl;

import com.aipaas.anycloud.common.error.exception.ClusterNotFoundException;
import com.aipaas.anycloud.common.error.exception.provisioning.CspStderrClassifier;
import com.aipaas.anycloud.domain.cluster.ClusterRepository;
import com.aipaas.anycloud.domain.cluster.admin.ClusterAdminCleanupService;
import com.aipaas.anycloud.domain.provisioning.VmClusterEntity;
import com.aipaas.anycloud.domain.provisioning.VmClusterRepository;
import io.aipaas.cluster.provisioning.core.PulumiCommandResult;
import io.aipaas.cluster.provisioning.service.PulumiCommandService;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * #7 — {@link AdminClusterCleanupController} 의 Repository 직접 호출 로직을 service 로 이전.
 * 컨트롤러는 dispatcher 만, TX 경계 + 도메인 로직은 본 클래스가 책임.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClusterAdminCleanupServiceImpl implements ClusterAdminCleanupService {

    private final ClusterRepository clusterRepository;
    private final VmClusterRepository vmClusterRepository;
    private final PulumiCommandService pulumiCommandService;

    @Override
    @Transactional
    public Map<String, Object> forceDelete(String clusterName) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("clusterName", clusterName);

        // VmCluster row — latest 1 row 만 (history retention 위해 multi-row 가능).
        var vmCluster = vmClusterRepository.findFirstByClusterNameOrderByCreatedAtDesc(clusterName);
        if (vmCluster.isEmpty()) {
            // Registered cluster path — vm_cluster 없이 cluster row 만 있을 수도.
            var cluster = clusterRepository.findById(clusterName);
            if (cluster.isEmpty()) {
                throw new ClusterNotFoundException(clusterName);
            }
            clusterRepository.delete(cluster.get());
            result.put("registeredClusterDeleted", true);
            log.warn("Force-deleted registered cluster {} (DB row only)", clusterName);
            return result;
        }

        VmClusterEntity vm = vmCluster.get();
        String stackName = vm.getStackName();
        String status =
                vm.getProvisioningStatus() != null ? vm.getProvisioningStatus().name() : "<unknown>";

        clusterRepository.findById(clusterName).ifPresent(clusterRepository::delete);
        vmClusterRepository.delete(vm);

        result.put("stackName", stackName);
        result.put("priorStatus", status);
        result.put("vmClusterDeleted", true);
        result.put("warning", "Pulumi state file (RustFS) + CSP 자원 (VPC/EC2) 은 별도 정리 필요");
        log.warn(
                "Force-deleted VM cluster {} (stack={}, priorStatus={}) — Pulumi state / CSP 자원 미정리",
                clusterName,
                stackName,
                status);
        return result;
    }

    @Override
    public Map<String, Object> cleanupOrphanState(String stackName) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("stackName", stackName);
        PulumiCommandResult cmd = pulumiCommandService.removeStackForce(stackName, Map.of());
        result.put("success", cmd.isSuccess());
        result.put("exitCode", cmd.getExitCode());
        if (!cmd.isSuccess()) {
            log.warn("Orphan state cleanup failed for stack {}: {}", stackName, cmd.getStderr());
            throw CspStderrClassifier.classifyPulumi("stack rm", cmd.getStderr());
        }
        log.warn("Orphan state cleanup done for stack {}", stackName);
        return result;
    }
}
