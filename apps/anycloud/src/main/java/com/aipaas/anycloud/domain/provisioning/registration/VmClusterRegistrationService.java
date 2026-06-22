package com.aipaas.anycloud.domain.provisioning.registration;

import com.aipaas.anycloud.domain.cluster.ClusterEntity;
import com.aipaas.anycloud.domain.provisioning.VmClusterEntity;

public interface VmClusterRegistrationService {

    /**
     * VM provisioning workflow 의 BOOTSTRAP step 에서 호출 — kubeadm 으로 K8s cluster 가
     * 구축된 직후 ClusterEntity row 를 생성한다 (status=AGENT_PENDING). 이후 agent helm install
     * → gRPC dial-in 으로 ACTIVE 전환.
     *
     * <p>vm_cluster.cluster_id FK 도 함께 SET.
     */
    ClusterEntity createClusterEntity(VmClusterEntity vmCluster);
}
