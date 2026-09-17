package com.aipaas.anycloud.domain.provisioning.query;

import com.aipaas.anycloud.domain.provisioning.api.request.ProvisionClusterRequest;
import com.aipaas.anycloud.domain.provisioning.api.response.VmClusterListItemResponse;
import com.aipaas.anycloud.domain.provisioning.api.response.VmClusterPreflightResponse;
import com.aipaas.anycloud.domain.provisioning.api.response.VmClusterStatusResponse;
import com.aipaas.anycloud.domain.provisioning.api.response.VmNodeListItemResponse;
import java.util.List;

public interface VmClusterQueryService {

    List<VmClusterListItemResponse> listVmClusters(String provider, String environment, String status);

    /**
     * 삭제 이력을 함께 볼 때 쓴다.
     *
     * <p>{@code status} 를 명시하면 그 필터가 우선한다 — 토글이 뒤집으면 필터가 거짓말이 된다.
     */
    List<VmClusterListItemResponse> listVmClusters(
            String provider, String environment, String status, boolean includeDeleted);

    VmClusterPreflightResponse preflightVmCluster(ProvisionClusterRequest cluster);

    com.aipaas.anycloud.domain.provisioning.api.response.VmClusterPreviewResponse previewVmCluster(
            ProvisionClusterRequest cluster);

    VmClusterStatusResponse getVmClusterStatus(String clusterName);

    /**
     * 클러스터 경계를 넘어 노드를 한 행씩 돌려준다.
     *
     * <p>삭제된 클러스터는 제외한다 — 남은 노드가 없다.
     *
     * @param provider null 이면 전체
     * @param clusterName null 이면 전체
     */
    List<VmNodeListItemResponse> listNodes(String provider, String clusterName);
}
