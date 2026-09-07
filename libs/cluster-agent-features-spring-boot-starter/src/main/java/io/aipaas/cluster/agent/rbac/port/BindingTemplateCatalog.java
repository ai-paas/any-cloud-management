package io.aipaas.cluster.agent.rbac.port;

import io.aipaas.cluster.agent.rbac.template.BindingTemplate;
import java.util.List;
import java.util.Map;

/** Binding template catalog SPI. */
public interface BindingTemplateCatalog {

    /** catalog 의 전체 binding 목록 (cluster 무관). */
    List<BindingTemplate> list();

    /** 주어진 cluster labels 매칭되는 template 들. {@code forClusters.matchLabels} 기준 필터링. */
    List<BindingTemplate> resolveFor(Map<String, String> clusterLabels);
}
