package io.aipaas.cluster.agent.observability.core;

import java.util.List;

/**
 * 사용자 정의 Grafana dashboard 들을 starter 에 제공하는 SPI.
 *
 * <p>호스트가 본 인터페이스를 구현해 Spring bean 으로 등록하면, monitoring stack 설치 후
 * {@link io.aipaas.cluster.agent.observability.stack.DefaultDashboardImporter} 가 starter 기본 dashboard
 * (cluster overview, GPU) 와 함께 자동 import. 여러 bean 등록 가능 — Spring 이 모두 수집.
 *
 * <p>각 Dashboard 는:
 * <ul>
 *   <li>{@code name} — ConfigMap 이름 (RFC 1123, cluster 내 unique)</li>
 *   <li>{@code json} — Grafana dashboard JSON (사용자 export 한 그대로)</li>
 *   <li>{@code requiresGpu} — true 면 GPU cluster 에만 적용 (dcgm 메트릭 기반 dashboard 등)</li>
 * </ul>
 *
 * <p>구현 예 — classpath resources 에서 로드:
 * <pre>
 * &#64;Component
 * public class FilesystemDashboardSource implements DashboardSource {
 *   public List&lt;Dashboard&gt; dashboards() {
 *     return List.of(
 *       new Dashboard("my-app", readResource("/dashboards/my-app.json"), false),
 *       new Dashboard("ml-jobs", readResource("/dashboards/ml-jobs.json"), true));
 *   }
 * }
 * </pre>
 *
 * <p>호스트가 미제공이면 starter 가 기본 2개 dashboard 만 import — backward compat.
 */
public interface DashboardSource {

	/**
	 * 본 source 가 제공하는 dashboard 목록. 매 install 호출마다 평가 — 동적 변경 가능.
	 *
	 * <p>빈 list 반환 OK. null 반환 시 starter 가 빈 list 로 treat.
	 */
	List<Dashboard> dashboards();

	/**
	 * @param name        ConfigMap 이름. RFC 1123 label 형식 권장 (소문자/숫자/하이픈).
	 * @param json        Grafana dashboard JSON 본문.
	 * @param requiresGpu true 면 GPU cluster (ClusterCapabilities.hasGpuNodes=true) 만 import.
	 */
	record Dashboard(String name, String json, boolean requiresGpu) {

		public Dashboard {
			if (name == null || name.isBlank()) {
				throw new IllegalArgumentException("dashboard name required");
			}
			if (json == null || json.isBlank()) {
				throw new IllegalArgumentException("dashboard json required");
			}
		}
	}
}
