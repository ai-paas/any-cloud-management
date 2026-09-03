package com.aipaas.anycloud.domain.provisioning.registration.internal;

import com.aipaas.anycloud.domain.cluster.ClusterEntity;
import com.aipaas.anycloud.domain.cluster.ClusterRepository;
import com.aipaas.anycloud.domain.provisioning.VmClusterEntity;
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
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public ClusterEntity createClusterEntity(VmClusterEntity vmCluster) {
        ClusterEntity clusterEntity = toClusterEntity(vmCluster);
        clusterRepository.save(clusterEntity);
        // FK link 명시 — vm_cluster.cluster_id 가 cluster.id 를 가리키도록.
        vmCluster.setClusterId(clusterEntity.getId());
        // Backend 는 cluster K8s API 를 직접 호출하지 않으므로 도달성 확인 없이 AGENT_PENDING.
        // cluster-agent 가 helm install 후 boot → gRPC dial-in 시점에 ACTIVE 로 전환됨.
        log.info("Provisioned cluster {} registered (AGENT_PENDING — agent dial-in 대기)", vmCluster.getClusterName());
        return clusterEntity;
    }

    private ClusterEntity toClusterEntity(VmClusterEntity vmCluster) {
        // VM provisioned cluster — agent 가 helm install 후 gRPC dial-in 으로 ACTIVE 전환.
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
     * VmClusterEntity.requestConfig (ProvisionClusterRequest JSON) 에서 hasGpuNodes 추출. parse 실패
     * 또는 미존재 시 false (default — agent 가 자동 backfill 가능하므로 안전).
     */
    private boolean extractHasGpuNodes(String requestConfigJson) {
        if (requestConfigJson == null || requestConfigJson.isBlank()) {
            return false;
        }
        try {
            JsonNode node = objectMapper.readTree(requestConfigJson);
            JsonNode flag = node.path("hasGpuNodes");
            return flag.isBoolean() && flag.asBoolean();
        } catch (Exception e) {
            log.debug("extractHasGpuNodes: parse failed, defaulting to false: {}", e.toString());
            return false;
        }
    }
}
