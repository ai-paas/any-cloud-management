package com.aipaas.anycloud.domain.provisioning.registration.internal;

import com.aipaas.anycloud.domain.cluster.ClusterEntity;
import com.aipaas.anycloud.domain.cluster.ClusterRepository;
import com.aipaas.anycloud.domain.cluster.model.GpuInstanceClassifier;
import com.aipaas.anycloud.domain.provisioning.VmClusterEntity;
import com.aipaas.anycloud.domain.provisioning.convergence.internal.RequestedAddonEnroller;
import com.aipaas.anycloud.domain.provisioning.registration.VmClusterRegistrationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class VmClusterRegistrationServiceImpl implements VmClusterRegistrationService {

    private final ClusterRepository clusterRepository;
    private final RequestedAddonEnroller requestedAddonEnroller;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public ClusterEntity createClusterEntity(VmClusterEntity vmCluster) {
        ClusterEntity clusterEntity = toClusterEntity(vmCluster);
        clusterRepository.save(clusterEntity);
        // FK link 명시: vm_cluster.cluster_id 가 cluster.id 를 가리키도록.
        vmCluster.setClusterId(clusterEntity.getId());
        // Backend 는 cluster K8s API 를 직접 호출하지 않으므로 도달성 확인 없이 AGENT_PENDING.
        // cluster-agent 가 helm install 후 기동, gRPC dial-in 시점에 ACTIVE 로 전환.
        // 요청이 함축하는 addon 은 PENDING 등록만 수행. 실제 설치는 agent 연결 후 cluster 가
        // ACTIVE 로 전이할 때 AddonOrchestrator 가 큐에 투입.
        requestedAddonEnroller.enroll(vmCluster);
        log.info("Provisioned cluster {} registered (AGENT_PENDING — agent dial-in 대기)", vmCluster.getClusterName());
        return clusterEntity;
    }

    private ClusterEntity toClusterEntity(VmClusterEntity vmCluster) {
        // VM provisioned cluster: agent 가 helm install 후 gRPC dial-in 으로 ACTIVE 전환.
        // kubeconfig material 은 vm_cluster.raw_outputs 에만 머물고 cluster row 에는 미저장.
        return ClusterEntity.builder()
                .id(vmCluster.getClusterName())
                .description(vmCluster.getDescription())
                .status(com.aipaas.anycloud.domain.cluster.model.ClusterStatus.AGENT_PENDING)
                .version(null)
                .clusterType("vm-kubeadm")
                .clusterProvider(vmCluster.getClusterProvider())
                .provisioningType("PULUMI")
                .provisioningStatus("READY")
                .stackName(vmCluster.getStackName())
                // VM 프로비저닝 요청 시 hasGpuNodes 가 requestConfig (ProvisionClusterRequest JSON) 에
                // 직렬화되어 들어있음. agent 가 노드 감지 시 backfill 로 재갱신.
                .hasGpuNodes(extractHasGpuNodes(vmCluster.getRequestConfig()))
                .build();
    }

    /**
     * requestConfig 의 플래그 우선 판정, 부재 시 선택한 인스턴스 타입으로 판정.
     *
     * <p>플래그는 화면 전송 시에만 채워지므로 API 직접 호출 시 빈 값. agent 의 backfill 은
     * 드라이버 기동 이후라 그 시점까지 GPU 클러스터가 일반 클러스터로 표시.
     */
    boolean extractHasGpuNodes(String requestConfigJson) {
        if (requestConfigJson == null || requestConfigJson.isBlank()) {
            return false;
        }
        try {
            JsonNode node = objectMapper.readTree(requestConfigJson);
            JsonNode flag = node.path("hasGpuNodes");
            if (flag.isBoolean() && flag.asBoolean()) {
                return true;
            }
            String provider = node.path("clusterProvider").asText(null);
            return GpuInstanceClassifier.isGpu(
                            provider, node.path("masterVmSpec").asText(null))
                    || GpuInstanceClassifier.isGpu(
                            provider, node.path("workerVmSpec").asText(null));
        } catch (Exception e) {
            log.debug("extractHasGpuNodes: parse failed, defaulting to false: {}", e.toString());
            return false;
        }
    }
}
