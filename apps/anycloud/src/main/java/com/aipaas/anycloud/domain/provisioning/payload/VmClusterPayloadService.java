package com.aipaas.anycloud.domain.provisioning.payload;

import com.aipaas.anycloud.domain.credential.ResolvedCspCredential;
import com.aipaas.anycloud.domain.provisioning.VmClusterEntity;
import com.aipaas.anycloud.domain.provisioning.api.request.ProvisionClusterRequest;
import com.aipaas.anycloud.domain.provisioning.api.response.VmClusterListItemResponse;
import com.aipaas.anycloud.domain.provisioning.api.response.VmClusterStatusResponse;
import io.aipaas.cluster.provisioning.api.ProvisioningRequest;
import java.util.Map;

public interface VmClusterPayloadService {

    String serializeRequestSnapshot(
            ProvisionClusterRequest cluster, ProvisioningRequest request, ResolvedCspCredential credential);

    /**
     * VmClusterEntity 의 requestConfig 스냅샷 + freshly-resolved credential 을 합쳐
     * {@link ProvisioningRequest} 를 복원. PROVISION step 재시도 (workflow message 에
     * provisioningRequest 가 비어있는 상태) 경로 전용.
     *
     * <p>스냅샷이 비어있거나 필수 필드(provider) 가 없으면 null 반환. 호출자는 null guard 후
     * 4xx 응답을 던져야 한다 — silently 작업 진행 시 worker NPE 폭발.
     */
    ProvisioningRequest restoreProvisioningRequest(VmClusterEntity vmCluster, ResolvedCspCredential credential);

    VmClusterListItemResponse buildListItemResponse(VmClusterEntity vmCluster);

    VmClusterStatusResponse buildStatusResponse(VmClusterEntity vmCluster);

    String serializeSanitizedOutputs(Map<String, Object> outputs);
}
