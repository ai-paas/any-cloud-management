package com.aipaas.anycloud.domain.agent.capabilities;

import com.aipaas.anycloud.domain.cluster.ClusterEntity;
import com.aipaas.anycloud.domain.cluster.ClusterRepository;
import io.aipaas.cluster.agent.observability.core.ClusterCapabilities;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * cluster-observability 의 {@link ClusterCapabilities} SPI 를 anycloud cluster 테이블 위에 구현.
 *
 * <p>현재 sources of truth:
 * <ul>
 *   <li>{@link ClusterEntity#getHasGpuNodes()} — VM provisioning 시 GPU flavor 선택 또는 운영자
 *       수동 설정으로 채워진 값 (Flyway V11 컬럼)</li>
 * </ul>
 *
 * <p>cluster 가 unknown 이면 false (exception 던지지 않음 — auto-installer 흐름 중단 회피).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AnycloudClusterCapabilities implements ClusterCapabilities {

    private final ClusterRepository clusterRepository;

    @Override
    @Transactional(readOnly = true)
    public boolean hasGpuNodes(String clusterName) {
        try {
            Optional<ClusterEntity> opt = clusterRepository.findById(clusterName);
            if (opt.isEmpty()) {
                log.debug("hasGpuNodes: cluster {} not found in repository", clusterName);
                return false;
            }
            Boolean v = opt.get().getHasGpuNodes();
            return Boolean.TRUE.equals(v);
        } catch (Exception e) {
            // DB 일시 오류 등 — auto-installer 흐름 보호.
            log.warn("hasGpuNodes lookup failed for cluster={}: {}", clusterName, e.toString());
            return false;
        }
    }
}
