package io.aipaas.cluster.agent.rbac.port;

import io.aipaas.cluster.agent.rbac.template.BindingTemplate;
import java.util.List;
import java.util.Map;

/**
 * Binding template catalog SPI.
 *
 * <p>default 구현 ({@code ClasspathBindingTemplateCatalog}) 은 classpath:{@code binding-templates.yaml}
 * 을 읽음. 호스트가 외부 storage / Git 동기화 / DB 등 다른 source 사용하면 본 SPI 만 override.
 *
 * <p>본 SPI 는 stateless — host application 의 storage 가 없어도 default 가 동작.
 */
public interface BindingTemplateCatalog {

	/** catalog 의 전체 binding 목록 (cluster 무관). */
	List<BindingTemplate> list();

	/** 주어진 cluster labels 매칭되는 template 들. {@code forClusters.matchLabels} 기준 필터링. */
	List<BindingTemplate> resolveFor(Map<String, String> clusterLabels);
}
