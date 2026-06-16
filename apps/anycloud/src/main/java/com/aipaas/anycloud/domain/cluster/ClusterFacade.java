package com.aipaas.anycloud.domain.cluster;

import com.aipaas.anycloud.domain.cluster.api.request.CreateClusterRequest;
import com.aipaas.anycloud.domain.cluster.api.request.PatchClusterRequest;
import com.aipaas.anycloud.domain.cluster.api.response.UnifiedClusterResponse;
import com.aipaas.anycloud.domain.operation.Operation;
import com.aipaas.anycloud.domain.operation.OperationEntity;
import java.util.List;

/**
 * 통합 cluster facade. {@code /v1/clusters} 컨트롤러가 사용하는 단일 진입점.
 * <p>
 * 내부적으로는 {@link com.aipaas.anycloud.domain.provisioning.VmClusterService} 또는
 * {@link ClusterService} 로 위임. source 필드로 분기.
 *
 * <p>CRUD 메서드가 {@code OperationEntity} 와 {@code Operation} 두 변형을 양립합니다.
 * 새 caller (controller / tests) 는 domain 변형 사용 권장. entity 변형은 점진 deprecate.
 * 자세한 로드맵: {@code docs/architecture/design/domain-model-roadmap.md}.
 */
public interface ClusterFacade {

    /**
     * 통합 list. source 가 null 이면 vm + registered 모두 반환.
     */
    List<UnifiedClusterResponse> list(String source, String provider, String environment, String status);

    /**
     * Paged variant. cursor 는 opaque 한 문자열로 controller 가 round-trip — 형식 ({@code vm:offset} /
     * {@code registered:offset}) 은 service 내부 구현 세부사항이며 client 가 파싱하면 안 된다. source 명시 시
     * 그 source 단일 page; 미명시 시 vm 먼저 exhaust 후 registered.
     *
     * @param pageSize 1..500.
     * @param pageToken null/blank 이면 첫 페이지. 응답 nextPageToken 을 그대로 round-trip.
     */
    PagedClusters listPaged(
            String source, String provider, String environment, String status, int pageSize, String pageToken);

    /** Page envelope — items + nextPageToken (null=마지막) + totalEstimate (집계 비용 가능한 경우만). */
    record PagedClusters(List<UnifiedClusterResponse> items, String nextPageToken, Long totalEstimate) {}

    UnifiedClusterResponse getOne(String clusterName);

    // ===== Entity 반환 (legacy) — 점진 deprecate =====

    /**
     * 생성. source 별로 spec 검증 후 비동기 시작 + operation 등록.
     * @return 시작된 operation
     */
    OperationEntity create(CreateClusterRequest request);

    /**
     * PATCH — workerCount 변경(SCALE) 트리거.
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
