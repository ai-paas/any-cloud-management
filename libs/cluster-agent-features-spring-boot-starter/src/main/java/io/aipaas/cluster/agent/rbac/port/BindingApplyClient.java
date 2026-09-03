package io.aipaas.cluster.agent.rbac.port;

import io.aipaas.cluster.agent.rbac.template.BindingTemplate;

/**
 * Binding apply/delete SPI.
 *
 * <p>default 구현 ({@code AgentBindingApplyClient}) 은 Layer 1 의 {@code KubeResourceService}
 * 를 통해 cluster 안 ClusterRoleBinding 에 apply.
 *
 * <p>label 규약 (모든 default 가 부착):
 * <ul>
 *   <li>{@code aipaas.io/managed-by = anycloud}</li>
 *   <li>{@code aipaas.io/template = <templateId>}</li>
 *   <li>{@code aipaas.io/oidc-group = <resolvedGroup>}</li>
 * </ul>
 *
 * <p>{@code resolvedGroup} 은 {@code template.oidcGroupSelector.matchExact} 의 단일 entry —
 * caller (addon hook / 운영자 UI) 가 selector 항목 별로 호출.
 */
public interface BindingApplyClient {

	/** 단일 (template, group) 조합에 대한 K8s binding 생성 또는 갱신. */
	ApplyResult apply(String clusterName, BindingTemplate template, String resolvedGroup, String actor);

	/** label selector {@code aipaas.io/template=<templateId>} 매칭되는 binding 일괄 삭제. */
	void deleteByTemplate(String clusterName, String templateId, String actor);

	/** label selector {@code aipaas.io/addon=<addonName>} 매칭되는 binding 일괄 삭제. */
	void deleteByAddon(String clusterName, String addonName, String actor);

	record ApplyResult(String k8sBindingName, String namespace) {}
}
