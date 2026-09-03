package com.aipaas.anycloud.domain.cluster;

import com.aipaas.anycloud.domain.cluster.api.request.CreateClusterRequest;
import com.aipaas.anycloud.domain.operation.OperationEntity;

/**
 * Cluster source 별 생성 strategy. ClusterFacadeImpl 이 source ID 로 dispatch.
 *
 * <pre>
 * &#64;Component class VmClusterProvider implements ClusterProvider { source = "vm"; ... }
 * &#64;Component class RegisteredClusterProvider implements ClusterProvider { source = "registered"; ... }
 *
 * &#64;Service class ClusterFacadeImpl {
 *     private final Map&lt;String, ClusterProvider&gt; byName;  // Spring 자동 주입
 *     public OperationEntity create(CreateClusterRequest req) {
 *         return byName.get(req.getSource().name()).create(req);
 *     }
 * }
 * </pre>
 */
public interface ClusterProvider {

    /** 처리할 source — {@code "vm"} 또는 {@code "registered"}. */
    String source();

    /** source 별 cluster 생성 진입점. 반환은 시작된 Operation. */
    OperationEntity create(CreateClusterRequest request);
}
