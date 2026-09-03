package com.aipaas.anycloud.domain.provisioning.convergence.internal;

import com.aipaas.anycloud.domain.provisioning.VmClusterEntity;
import com.aipaas.anycloud.domain.provisioning.convergence.ClusterComponentObserver;
import com.aipaas.anycloud.domain.provisioning.convergence.ClusterConvergenceService;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * VERIFY 단계 안에서 도는 시간 제한 수렴 루프.
 *
 * <p>RabbitMQ consumer 스레드 위에서 실행되므로 오래 붙잡으면 consumer 하나가 통째로 묶인다.
 * 기본 3회, 회당 1분이라 최대 3분이다. 그 이상 걸리는 수렴은 조정 루프가 맡는다.
 */
@Slf4j
@Service
public class ClusterConvergenceServiceImpl implements ClusterConvergenceService {

    private final ClusterComponentObserver observer;
    private final int maxAttempts;
    private final Duration attemptInterval;

    public ClusterConvergenceServiceImpl(
            ClusterComponentObserver observer,
            @Value("${anycloud.vm-cluster.convergence.verify-max-attempts:3}") int maxAttempts,
            @Value("${anycloud.vm-cluster.convergence.verify-interval:PT1M}") Duration attemptInterval) {
        this.observer = observer;
        this.maxAttempts = maxAttempts;
        this.attemptInterval = attemptInterval;
    }

    @Override
    public boolean convergeWithinBudget(VmClusterEntity vmCluster) {
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            ConvergenceVerdict verdict;
            try {
                verdict = ClusterConvergenceOrchestratorImpl.evaluate(observer.observe(vmCluster));
            } catch (Exception e) {
                log.warn(
                        "수렴 관측 실패 cluster={} attempt={}: {}",
                        vmCluster.getClusterName(),
                        attempt,
                        e.toString());
                return false;
            }
            if (verdict == ConvergenceVerdict.SATISFIED) {
                return true;
            }
            if (attempt < maxAttempts && !sleepBetweenAttempts()) {
                return false;
            }
        }
        return false;
    }

    /** interrupt 를 삼키면 종료 신호가 사라진다. 플래그를 복구하고 즉시 포기한다. */
    private boolean sleepBetweenAttempts() {
        if (attemptInterval.isZero() || attemptInterval.isNegative()) {
            return true;
        }
        try {
            Thread.sleep(attemptInterval.toMillis());
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
