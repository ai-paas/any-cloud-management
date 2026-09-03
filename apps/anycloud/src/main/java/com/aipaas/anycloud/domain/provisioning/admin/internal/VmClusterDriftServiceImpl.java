package com.aipaas.anycloud.domain.provisioning.admin.internal;

import com.aipaas.anycloud.common.error.enums.ErrorCode;
import com.aipaas.anycloud.common.error.exception.ClusterNotFoundException;
import com.aipaas.anycloud.common.error.exception.provisioning.PermanentProvisioningFailure;
import com.aipaas.anycloud.common.error.exception.provisioning.TransientProvisioningFailure;
import com.aipaas.anycloud.domain.credential.CspCredentialService;
import com.aipaas.anycloud.domain.credential.ResolvedCspCredential;
import com.aipaas.anycloud.domain.provisioning.VmClusterEntity;
import com.aipaas.anycloud.domain.provisioning.VmClusterRepository;
import com.aipaas.anycloud.domain.provisioning.admin.VmClusterDriftService;
import com.aipaas.anycloud.domain.provisioning.payload.VmClusterPayloadService;
import io.aipaas.cluster.provisioning.api.ProvisioningPreview;
import io.aipaas.cluster.provisioning.api.ProvisioningRequest;
import io.aipaas.cluster.provisioning.api.ProvisioningService;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Drift detection + state refresh admin 기능.
 *
 * <ul>
 *   <li>{@code detectDrift} — Automation API preview 가 changeSummary 반환 (변경 0 이면 drift 없음).
 *   <li>{@code refreshState} — Automation API refresh 가 stack state 와 실 인프라 동기화.
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VmClusterDriftServiceImpl implements VmClusterDriftService {

    private final VmClusterRepository vmClusterRepository;
    private final CspCredentialService cspCredentialService;
    private final VmClusterPayloadService vmClusterPayloadService;
    private final ProvisioningService provisioningService;

    @Override
    public Map<String, Object> detectDrift(String clusterName) {
        VmClusterEntity vmCluster = requireVmClusterWithStack(clusterName);
        ProvisioningRequest request = restoreRequest(vmCluster);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("clusterName", clusterName);
        result.put("stackName", vmCluster.getStackName());

        try {
            ProvisioningPreview preview = provisioningService.preview(request);
            result.put("drifted", preview.hasChanges());
            result.put("changeSummary", preview.changeSummary());
            // Automation API PreviewResult 가 step 단위 detail 미제공 — changeSummary op 별 count 만.
            result.put("steps", java.util.List.of());
        } catch (RuntimeException e) {
            log.warn("Drift detection failed for cluster {}: {}", clusterName, e.getMessage());
            throw new TransientProvisioningFailure("drift detection failed: " + e.getMessage(), e);
        }
        return result;
    }

    @Override
    public Map<String, Object> refreshState(String clusterName) {
        VmClusterEntity vmCluster = requireVmClusterWithStack(clusterName);
        ProvisioningRequest request = restoreRequest(vmCluster);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("clusterName", clusterName);
        result.put("stackName", vmCluster.getStackName());

        try {
            provisioningService.refresh(request);
            result.put("success", true);
            log.info("Pulumi state refreshed for stack {} (cluster {})", vmCluster.getStackName(), clusterName);
        } catch (RuntimeException e) {
            log.warn("Pulumi refresh failed for stack {}: {}", vmCluster.getStackName(), e.getMessage());
            throw new TransientProvisioningFailure("refresh failed: " + e.getMessage(), e);
        }
        return result;
    }

    private VmClusterEntity requireVmClusterWithStack(String clusterName) {
        VmClusterEntity vmCluster = vmClusterRepository
                .findFirstByClusterNameOrderByCreatedAtDesc(clusterName)
                .orElseThrow(() -> new ClusterNotFoundException(clusterName));
        if (vmCluster.getStackName() == null || vmCluster.getStackName().isBlank()) {
            throw new ClusterNotFoundException(clusterName + " (no Pulumi stack)");
        }
        return vmCluster;
    }

    /** VmClusterEntity + 새로 resolve 한 credential → ProvisioningRequest. */
    private ProvisioningRequest restoreRequest(VmClusterEntity vmCluster) {
        ResolvedCspCredential credential =
                cspCredentialService.resolveForProvision(vmCluster.getClusterProvider(), vmCluster.getCredentialId());
        ProvisioningRequest request = vmClusterPayloadService.restoreProvisioningRequest(vmCluster, credential);
        if (request == null) {
            throw new PermanentProvisioningFailure(
                    "Failed to restore ProvisioningRequest from vm_cluster.request_config (cluster="
                            + vmCluster.getClusterName() + ")",
                    ErrorCode.PROVISIONING_REQUEST_MISSING);
        }
        return request;
    }
}
