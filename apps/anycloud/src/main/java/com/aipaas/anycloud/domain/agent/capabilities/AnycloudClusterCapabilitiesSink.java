package com.aipaas.anycloud.domain.agent.capabilities;

import com.aipaas.anycloud.domain.cluster.ClusterEntity;
import com.aipaas.anycloud.domain.cluster.ClusterRepository;
import com.aipaas.anycloud.domain.provisioning.capability.VmClusterGpuSpec;
import io.aipaas.cluster.agent.observability.port.ClusterCapabilitiesSink;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * cluster-observability 의 {@link ClusterCapabilitiesSink} SPI 를 anycloud cluster 테이블 위에 구현.
 *
 * <p>{@link io.aipaas.cluster.agent.observability.stack.GpuCapabilityHeartbeatListener} 가 호출
 * 측에서 변경 감지 후에만 호출하나, 이 구현도 추가 dirty check (DB 값 == 목표값 이면 skip) 로
 * race 상황의 중복 write 회피.
 *
 * <p>예외는 로그만 기록하고 미전파. listener 측 try/catch 와 함께 2중 차단.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AnycloudClusterCapabilitiesSink implements ClusterCapabilitiesSink {

    private final ClusterRepository clusterRepository;
    private final ObjectProvider<VmClusterGpuSpec> gpuSpecProvider;

    @Override
    @Transactional
    public void setHasGpuNodes(String clusterName, boolean value) {
        try {
            Optional<ClusterEntity> opt = clusterRepository.findById(clusterName);
            if (opt.isEmpty()) {
                log.debug("setHasGpuNodes: cluster {} not found — skip", clusterName);
                return;
            }
            /*
             * heartbeat 의 gpu_node_count 는 드라이버 기동 전까지 0. 그대로 사용하면 GPU 로
             * 생성한 cluster 가 false 로 전환돼 화면의 가속기 영역이 사라지고 gpu-operator
             * 자동 등록도 생략. 요청이 GPU 인 경우 하향 미적용.
             */
            if (!value && requestedGpuNodes(clusterName)) {
                log.debug("setHasGpuNodes: cluster {} 는 GPU 요청이라 false 로 내리지 않는다", clusterName);
                return;
            }
            ClusterEntity entity = opt.get();
            Boolean current = entity.getHasGpuNodes();
            if (current != null && current.booleanValue() == value) {
                // 이미 같은 값: skip.
                return;
            }
            entity.setHasGpuNodes(value);
            clusterRepository.save(entity);
            log.info("cluster.has_gpu_nodes updated cluster={} {} → {}", clusterName, current, value);
        } catch (Exception e) {
            log.warn("setHasGpuNodes failed cluster={} target={}: {}", clusterName, value, e.toString());
        }
    }

    private boolean requestedGpuNodes(String clusterName) {
        VmClusterGpuSpec gpuSpec = gpuSpecProvider.getIfAvailable();
        return gpuSpec != null && gpuSpec.requestedGpuNodes(clusterName);
    }
}
