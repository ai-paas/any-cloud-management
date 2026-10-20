package com.aipaas.anycloud.domain.cluster;

import com.aipaas.anycloud.domain.cluster.api.request.CreateClusterRequest;
import com.aipaas.anycloud.domain.cluster.api.request.PatchClusterRequest;
import com.aipaas.anycloud.domain.cluster.api.response.UnifiedClusterResponse;
import com.aipaas.anycloud.domain.operation.Operation;
import com.aipaas.anycloud.domain.operation.OperationEntity;
import java.util.List;

/** 통합 cluster facade. {@code /v1/clusters} 컨트롤러가 사용하는 단일 진입점. */
public interface ClusterFacade {

    /** source 가 null 이면 vm 과 registered 를 모두 돌려줍니다. */
    List<UnifiedClusterResponse> list(String source, String provider, String environment, String status);

    /**
     * 페이지 단위 조회입니다. source 를 지정하면 그 source 만, 비워 두면 vm 을 먼저 다 돌고
     * registered 로 넘어갑니다.
     *
     * <p>{@code pageToken} 은 불투명한 값입니다. 형식은 내부 구현이라 바뀔 수 있으니 받은 값을
     * 그대로 돌려보내기만 하세요.
     *
     * @param pageSize 1..500.
     * @param pageToken null 이거나 비어 있으면 첫 페이지.
     */
    PagedClusters listPaged(
            String source, String provider, String environment, String status, int pageSize, String pageToken);

    /** Page envelope: items + nextPageToken (null=마지막) + totalEstimate (집계 비용 가능한 경우만). */
    record PagedClusters(List<UnifiedClusterResponse> items, String nextPageToken, Long totalEstimate) {}

    UnifiedClusterResponse getOne(String clusterName);

    // ===== Entity 반환 (legacy). 점진 deprecate =====

    /**
     * 생성. source 별로 spec 검증 후 비동기 시작 + operation 등록.
     * @return 시작된 operation
     */
    OperationEntity create(CreateClusterRequest request);

    /**
     * PATCH: workerCount 변경(SCALE) 트리거.
     * @return 시작된 operation
     */
    OperationEntity patch(String clusterName, PatchClusterRequest request);

    /**
     * 삭제. 비동기 destroy → operation.
     */
    OperationEntity delete(String clusterName);

    /**
     * 액션 operation. retryWorkflow / retryRegistration / refreshStatus 중 하나.
     */
    OperationEntity createOperation(String clusterName, String type);

    // ===== Domain 반환 =====

    /** {@link #create(CreateClusterRequest)} 의 domain 변형. */
    Operation createDomain(CreateClusterRequest request);

    /** {@link #patch(String, PatchClusterRequest)} 의 domain 변형. */
    Operation patchDomain(String clusterName, PatchClusterRequest request);

    /** {@link #delete(String)} 의 domain 변형. */
    Operation deleteDomain(String clusterName);

    /** {@link #createOperation(String, String)} 의 domain 변형. */
    Operation createOperationDomain(String clusterName, String type);

    /**
     * 클러스터 K8s API 연결 검증 (검사 결과 자체가 자원).
     */
    boolean checkConnectivity(String clusterName);
}
