package com.aipaas.anycloud.domain.cluster.health;

import com.aipaas.anycloud.domain.cluster.ClusterService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
@Profile("!vm-cluster-worker")
@ConditionalOnProperty(
        prefix = "vm-cluster-workflow",
        name = "worker-enabled",
        havingValue = "false",
        matchIfMissing = true)
public class ClusterStatusScheduler {

    private final ClusterService clusterService;

    @Scheduled(fixedRate = 300000) // 5분마다 실행 (300000ms)
    public void updateClusterStatuses() {
        log.info("Starting scheduled cluster status update");
        // clusterService.updateAllClusterStatuses(); // 공인인증 이후 주석 해제 예정
    }
}
