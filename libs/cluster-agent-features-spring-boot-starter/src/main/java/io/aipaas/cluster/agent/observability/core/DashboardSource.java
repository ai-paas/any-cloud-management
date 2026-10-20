package io.aipaas.cluster.agent.observability.core;

import java.util.List;

/**
 * 호스트가 자기 Grafana dashboard 를 끼워 넣는 SPI 입니다. 구현해서 Spring bean 으로 등록하면
 * monitoring stack 설치 후 starter 기본 dashboard 와 함께 import 됩니다. bean 을 여러 개 둬도
 * 모두 모읍니다. 구현하지 않으면 기본 dashboard 만 들어갑니다.
 *
 * <p>{@code name} 은 ConfigMap 이름이라 RFC 1123 을 지켜야 하고 cluster 안에서 겹치면 안 됩니다.
 * {@code json} 은 Grafana 에서 export 한 그대로 넣으면 됩니다. {@code requiresGpu} 가 true 면
 * GPU cluster 에만 들어갑니다.
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
