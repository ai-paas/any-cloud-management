package com.aipaas.anycloud.domain.kube.internal;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Cluster agent 의 degraded 응답 분류 결과를 Prometheus counter 로 집계.
 *
 * <p>MeterRegistry 가 미주입된 환경 (test 등) 에서도 동작하도록 ObjectProvider lazy lookup — 등록
 * 실패는 fail-open (audit 정책과 동일).
 *
 * <p>kind 라벨은 GVR 식별 가능한 normalize string 으로 변환되어 cardinality 폭주를 막는다
 * (예: {@code pods}, {@code applications.argoproj.io}).
 */
@Slf4j
@Component
@RequiredArgsConstructor
class KubeDegradedMetricsRecorder {

    private static final String METRIC = "cluster_agent.degraded.count";

    private final ObjectProvider<MeterRegistry> meterRegistryProvider;

    void record(String clusterName, String kind, String reason) {
        try {
            MeterRegistry registry = meterRegistryProvider.getIfAvailable();
            if (registry == null) return;
            Counter.builder(METRIC)
                    .description("K8s degraded response classified by reason.")
                    .tag("cluster", clusterName == null ? "unknown" : clusterName)
                    .tag("kind", KubeErrorClassifier.normalizeKindLabel(kind))
                    .tag("reason", reason == null ? "UNKNOWN" : reason)
                    .register(registry)
                    .increment();
        } catch (RuntimeException e) {
            log.debug("recordDegraded failed (non-fatal): {}", e.toString());
        }
    }
}
