package io.aipaas.cluster.agent.observability.port;

import java.util.List;

/**
 * 모니터링 대상 cluster 목록을 starter 에 제공하는 SPI.
 *
 * <p>호스트 애플리케이션이 본 인터페이스를 구현해서 Spring bean 으로 등록하면 starter 가 자동 inject.
 * Anycloud 는 ClusterRepository 위에서 구현 — 다른 프로젝트는 정적 list / 외부 API call 등 자유.
 *
 * <p>본 SPI 는 "어떤 cluster 들을 알고 있나" 만 책임 — 각 cluster 의 agent 가 ACTIVE 인지는 cluster-agent
 * starter 의 {@link io.aipaas.cluster.agent.runtime.AgentSessionRegistry} 가 결정.
 */
public interface ClusterCatalog {

	/**
	 * 모니터링 가능한 cluster 의 식별자 목록.
	 *
	 * <p>"모니터링 가능" 의 정의는 구현체 자유 — anycloud 는 ClusterStatus = ACTIVE 인 cluster 만 반환,
	 * 다른 프로젝트는 다른 기준 가능.
	 */
	List<String> listClusterNames();
}
