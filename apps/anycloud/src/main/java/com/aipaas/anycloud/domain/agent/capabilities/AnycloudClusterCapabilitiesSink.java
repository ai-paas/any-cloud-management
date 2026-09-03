package com.aipaas.anycloud.domain.agent.capabilities;

import com.aipaas.anycloud.domain.cluster.ClusterEntity;
import com.aipaas.anycloud.domain.cluster.ClusterRepository;
import io.aipaas.cluster.agent.observability.port.ClusterCapabilitiesSink;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * cluster-observability 의 {@link ClusterCapabilitiesSink} SPI 를 anycloud cluster 테이블 위에 구현.
 *
 * <p>{@link io.aipaas.cluster.agent.observability.stack.GpuCapabilityHeartbeatListener} 가 sink 호출 측
 * 에서 변경 감지 후에만 호출하지만, 본 구현도 추가 dirty check (DB 값 == 목표값 이면 skip) 로 race
 * 발생 시 redundant write 회피.
 *
 * <p>예외 발생 시 swallow + log — listener 의 try/catch 와 더불어 2 단계 안전망.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AnycloudClusterCapabilitiesSink implements ClusterCapabilitiesSink {

    private final ClusterRepository clusterRepository;

    @Override
    @Transactional
    public void setHasGpuNodes(String clusterName, boolean value) {
        try {
            Optional<ClusterEntity> opt = clusterRepository.findById(clusterName);
            if (opt.isEmpty()) {
                log.debug("setHasGpuNodes: cluster {} not found — skip", clusterName);
                return;
            }
            ClusterEntity entity = opt.get();
            Boolean current = entity.getHasGpuNodes();
            if (current != null && current.booleanValue() == value) {
                // 이미 같은 값 — skip.
                return;
            }
            entity.setHasGpuNodes(value);
            clusterRepository.save(entity);
            log.info("cluster.has_gpu_nodes updated cluster={} {} → {}", clusterName, current, value);
        } catch (Exception e) {
            log.warn("setHasGpuNodes failed cluster={} target={}: {}", clusterName, value, e.toString());
        }
    }
}
