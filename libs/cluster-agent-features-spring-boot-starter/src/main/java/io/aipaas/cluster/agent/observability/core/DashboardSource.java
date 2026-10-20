package io.aipaas.cluster.agent.observability.core;

import java.util.List;

/**
 * 호스트가 자체 Grafana dashboard 를 추가하는 SPI. 구현 후 Spring bean 으로 등록하면 monitoring
 * stack 설치 후 starter 기본 dashboard 와 함께 import. bean 이 여럿이면 전부 수집. 미구현 시
 * 기본 dashboard 만 적용.
 *
 * <p>{@code name} 은 ConfigMap 이름이므로 RFC 1123 준수 필요, cluster 내 중복 금지.
 * {@code json} 은 Grafana export 결과를 그대로 사용. {@code requiresGpu} 가 true 면 GPU
 * cluster 에만 적용.
 *
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
 */
public interface DashboardSource {

    /**
     * 본 source 가 제공하는 dashboard 목록. 매 install 호출마다 평가: 동적 변경 가능.
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
