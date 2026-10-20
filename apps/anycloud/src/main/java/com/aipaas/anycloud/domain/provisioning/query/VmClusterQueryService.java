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
     * 삭제 이력 포함 조회 시 사용.
     *
     * <p>{@code status} 명시 시 해당 필터 우선. 토글이 필터를 덮으면 필터 결과가 요청과 불일치.
     */
    List<VmClusterListItemResponse> listVmClusters(
            String provider, String environment, String status, boolean includeDeleted);

    VmClusterPreflightResponse preflightVmCluster(ProvisionClusterRequest cluster);

    com.aipaas.anycloud.domain.provisioning.api.response.VmClusterPreviewResponse previewVmCluster(
            ProvisionClusterRequest cluster);

    VmClusterStatusResponse getVmClusterStatus(String clusterName);

    /**
     * 클러스터 구분 없이 노드를 행 단위로 반환.
     *
     * <p>삭제된 클러스터 제외. 잔존 노드 없음.
     *
     * @param provider null 이면 전체
     * @param clusterName null 이면 전체
     */
    List<VmNodeListItemResponse> listNodes(String provider, String clusterName);
}
