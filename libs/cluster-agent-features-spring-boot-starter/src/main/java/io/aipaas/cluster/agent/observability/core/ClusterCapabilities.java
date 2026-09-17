package io.aipaas.cluster.agent.observability.core;

/** Cluster 의 하드웨어 / 운영 capability 정보를 starter 에 제공하는 SPI. */
public interface ClusterCapabilities {

    /**
     * 대상 cluster 에 GPU 노드가 있는지.
     *
     * <p>true 이면 auto-installer 가 dcgm-exporter 도 함께 설치. false 또는 unknown 이면 generic
     * kube-prometheus-stack 만 설치.
     *
     * <p>Cluster 가 unknown 인 경우 false 반환 권장 (예외 throw 금지 — auto-install 흐름이 중단됨).
     */
    boolean hasGpuNodes(String clusterName);
}
