package io.aipaas.cluster.agent.observability.core;
import io.aipaas.cluster.agent.observability.port.ClusterCatalog;

/**
 * Cluster 의 하드웨어 / 운영 capability 정보를 starter 에 제공하는 SPI.
 *
 * <p>{@link ClusterCatalog} 가 "어떤 cluster 가 모니터링 가능한가?" 를 책임진다면, 본 SPI 는 "그 cluster
 * 가 어떤 능력을 가졌는가? (GPU 노드 존재 등)" 를 책임진다. 두 SPI 모두 호스트가 구현.
 *
 * <p>Anycloud 는 ClusterEntity 의 has_gpu_nodes 컬럼 위에 구현 — VM provisioning 시 GPU flavor
 * 선택되면 true 로 세팅. 다른 프로젝트는 K8s node label 스캔 / 외부 inventory API 등 자유.
 *
 * <p>본 SPI 가 없어도 starter 는 동작 — auto-installer 가 default false 로 treat (GPU exporter 미설치).
 */
public interface ClusterCapabilities {

	/**
	 * 대상 cluster 에 GPU 노드가 있는지.
	 *
	 * <p>true 이면 auto-installer 가 dcgm-exporter 도 함께 설치한다. false 또는 unknown 이면 generic
	 * kube-prometheus-stack 만 설치.
	 *
	 * <p>Cluster 가 unknown 인 경우 false 반환 권장 (예외 throw 금지 — auto-install 흐름이 중단됨).
	 */
	boolean hasGpuNodes(String clusterName);
}
