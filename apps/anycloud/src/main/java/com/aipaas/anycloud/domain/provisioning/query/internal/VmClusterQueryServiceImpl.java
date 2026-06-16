package com.aipaas.anycloud.domain.provisioning.query.internal;

import com.aipaas.anycloud.common.error.exception.ClusterNotFoundException;
import com.aipaas.anycloud.domain.provisioning.VmClusterEntity;
import com.aipaas.anycloud.domain.provisioning.VmClusterRepository;
import com.aipaas.anycloud.domain.provisioning.api.request.ProvisionClusterRequest;
import com.aipaas.anycloud.domain.provisioning.api.response.VmClusterListItemResponse;
import com.aipaas.anycloud.domain.provisioning.api.response.VmClusterPreflightResponse;
import com.aipaas.anycloud.domain.provisioning.api.response.VmClusterStatusResponse;
import com.aipaas.anycloud.domain.provisioning.model.VmClusterStatus;
import com.aipaas.anycloud.domain.provisioning.payload.VmClusterPayloadService;
import com.aipaas.anycloud.domain.provisioning.preflight.VmClusterPreflightService;
import com.aipaas.anycloud.domain.provisioning.query.VmClusterQueryService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * VM cluster read-only query — list / status. preflight 검증은 {@link VmClusterPreflightService} 로 위임.
 *
 * <p>책임:
 * <ul>
 *   <li>{@link #listVmClusters} — filter spec + payload conversion</li>
 *   <li>{@link #getVmClusterStatus} — single cluster status</li>
 *   <li>{@link #preflightVmCluster} — delegate</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class VmClusterQueryServiceImpl implements VmClusterQueryService {

    private final VmClusterRepository vmClusterRepository;
    private final VmClusterPayloadService vmClusterPayloadService;
    private final VmClusterPreflightService preflightService;

    @Override
    public List<VmClusterListItemResponse> listVmClusters(String provider, String environment, String status) {
        VmClusterStatus normalizedStatus = normalizeStatus(status);
        Specification<VmClusterEntity> specification = Specification.where(
                        equalsIgnoreCase("clusterProvider", provider))
                .and(equalsIgnoreCase("environment", environment))
                .and(equalsEnum("provisioningStatus", normalizedStatus));

        return vmClusterRepository.findAll(specification, Sort.by(Sort.Direction.DESC, "createdAt")).stream()
                .map(vmClusterPayloadService::buildListItemResponse)
                .toList();
    }

    @Override
    public VmClusterPreflightResponse preflightVmCluster(ProvisionClusterRequest cluster) {
        // preflight 책임은 VmClusterPreflightService 로 분리. caller 변경 0.
        return preflightService.preflightVmCluster(cluster);
    }

    @Override
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public com.aipaas.anycloud.domain.provisioning.api.response.VmClusterPreviewResponse previewVmCluster(
            ProvisionClusterRequest cluster) {
        return preflightService.previewVmCluster(cluster);
    }

    @Override
    public VmClusterStatusResponse getVmClusterStatus(String clusterName) {
        VmClusterEntity vmCluster = getVmCluster(clusterName);
        return vmClusterPayloadService.buildStatusResponse(vmCluster);
    }

    private VmClusterEntity getVmCluster(String clusterName) {
        return vmClusterRepository
                .findFirstByClusterNameOrderByCreatedAtDesc(clusterName)
                .orElseThrow(() -> new ClusterNotFoundException(clusterName));
    }

    private VmClusterStatus normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return VmClusterStatus.valueOf(status.toUpperCase());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private Specification<VmClusterEntity> equalsIgnoreCase(String fieldName, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return (root, query, builder) -> builder.equal(builder.lower(root.get(fieldName)), value.toLowerCase());
    }

    private Specification<VmClusterEntity> equalsEnum(String fieldName, Enum<?> value) {
        if (value == null) {
            return null;
        }
        return (root, query, builder) -> builder.equal(root.get(fieldName), value);
    }
}
