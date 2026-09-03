package com.aipaas.anycloud.domain.provisioning;

import com.aipaas.anycloud.domain.provisioning.api.request.ProvisionClusterRequest;
import com.aipaas.anycloud.domain.provisioning.api.response.VmClusterListItemResponse;
import com.aipaas.anycloud.domain.provisioning.api.response.VmClusterPreflightResponse;
import com.aipaas.anycloud.domain.provisioning.api.response.VmClusterStatusResponse;
import java.util.List;
import org.springframework.http.HttpStatus;

public interface VmClusterService {

    List<VmClusterListItemResponse> listVmClusters(String provider, String environment, String status);

    VmClusterPreflightResponse preflightVmCluster(ProvisionClusterRequest cluster);

    com.aipaas.anycloud.domain.provisioning.api.response.VmClusterPreviewResponse previewVmCluster(
            ProvisionClusterRequest cluster);

    HttpStatus createVmCluster(ProvisionClusterRequest cluster);

    VmClusterStatusResponse getVmClusterStatus(String clusterName);

    HttpStatus retryVmClusterRegistration(String clusterName);

    HttpStatus retryVmClusterWorkflow(String clusterName);

    HttpStatus scaleVmCluster(String clusterName, int workerCount);

    HttpStatus deleteVmCluster(String clusterName);
}
