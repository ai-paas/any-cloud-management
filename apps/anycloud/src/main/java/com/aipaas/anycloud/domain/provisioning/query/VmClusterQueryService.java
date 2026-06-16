package com.aipaas.anycloud.domain.provisioning.query;

import com.aipaas.anycloud.domain.provisioning.api.request.ProvisionClusterRequest;
import com.aipaas.anycloud.domain.provisioning.api.response.VmClusterListItemResponse;
import com.aipaas.anycloud.domain.provisioning.api.response.VmClusterPreflightResponse;
import com.aipaas.anycloud.domain.provisioning.api.response.VmClusterStatusResponse;
import java.util.List;

public interface VmClusterQueryService {

    List<VmClusterListItemResponse> listVmClusters(String provider, String environment, String status);

    VmClusterPreflightResponse preflightVmCluster(ProvisionClusterRequest cluster);

    com.aipaas.anycloud.domain.provisioning.api.response.VmClusterPreviewResponse previewVmCluster(
            ProvisionClusterRequest cluster);

    VmClusterStatusResponse getVmClusterStatus(String clusterName);
}
