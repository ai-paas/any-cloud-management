package com.aipaas.anycloud.domain.provisioning.internal;

import com.aipaas.anycloud.domain.provisioning.VmClusterService;
import com.aipaas.anycloud.domain.provisioning.api.request.ProvisionClusterRequest;
import com.aipaas.anycloud.domain.provisioning.api.response.VmClusterListItemResponse;
import com.aipaas.anycloud.domain.provisioning.api.response.VmClusterPreflightResponse;
import com.aipaas.anycloud.domain.provisioning.api.response.VmClusterStatusResponse;
import com.aipaas.anycloud.domain.provisioning.command.VmClusterCommandService;
import com.aipaas.anycloud.domain.provisioning.query.VmClusterQueryService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class VmClusterServiceImpl implements VmClusterService {

    private final VmClusterQueryService vmClusterQueryService;
    private final VmClusterCommandService vmClusterCommandService;

    @Override
    @Transactional(readOnly = true)
    public List<VmClusterListItemResponse> listVmClusters(String provider, String environment, String status) {
        return vmClusterQueryService.listVmClusters(provider, environment, status);
    }

    @Override
    @Transactional(readOnly = true)
    public VmClusterPreflightResponse preflightVmCluster(ProvisionClusterRequest cluster) {
        return vmClusterQueryService.preflightVmCluster(cluster);
    }

    @Override
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public com.aipaas.anycloud.domain.provisioning.api.response.VmClusterPreviewResponse previewVmCluster(
            ProvisionClusterRequest cluster) {
        // Pulumi CLI + CSP API 호출로 수십 초 — TX/connection 점유 없이 실행 (provision 과 동일 원칙).
        return vmClusterQueryService.previewVmCluster(cluster);
    }

    @Override
    public HttpStatus createVmCluster(ProvisionClusterRequest cluster) {
        return vmClusterCommandService.createVmCluster(cluster);
    }

    @Override
    @Transactional(readOnly = true)
    public VmClusterStatusResponse getVmClusterStatus(String clusterName) {
        return vmClusterQueryService.getVmClusterStatus(clusterName);
    }

    @Override
    public HttpStatus retryVmClusterRegistration(String clusterName) {
        return vmClusterCommandService.retryVmClusterRegistration(clusterName);
    }

    @Override
    public HttpStatus retryVmClusterWorkflow(String clusterName) {
        return vmClusterCommandService.retryVmClusterWorkflow(clusterName);
    }

    @Override
    public HttpStatus scaleVmCluster(String clusterName, int workerCount) {
        return vmClusterCommandService.scaleVmCluster(clusterName, workerCount);
    }

    @Override
    public HttpStatus deleteVmCluster(String clusterName) {
        return vmClusterCommandService.deleteVmCluster(clusterName);
    }
}
