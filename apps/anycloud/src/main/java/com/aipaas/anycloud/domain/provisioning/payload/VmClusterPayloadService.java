package com.aipaas.anycloud.domain.provisioning.payload;

import com.aipaas.anycloud.domain.credential.ResolvedCspCredential;
import com.aipaas.anycloud.domain.provisioning.VmClusterEntity;
import com.aipaas.anycloud.domain.provisioning.api.request.ProvisionClusterRequest;
import com.aipaas.anycloud.domain.provisioning.api.response.VmClusterListItemResponse;
import com.aipaas.anycloud.domain.provisioning.api.response.VmClusterStatusResponse;
import io.aipaas.cluster.provisioning.core.ProvisioningRequest;
import java.util.Map;

public interface VmClusterPayloadService {

    String serializeRequestSnapshot(
            ProvisionClusterRequest cluster, ProvisioningRequest request, ResolvedCspCredential credential);

    VmClusterListItemResponse buildListItemResponse(VmClusterEntity vmCluster);

    VmClusterStatusResponse buildStatusResponse(VmClusterEntity vmCluster);

    String serializeSanitizedOutputs(Map<String, Object> outputs);
}
