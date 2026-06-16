package io.aipaas.cluster.agent.rbac.internal;

import com.fasterxml.jackson.databind.JsonNode;
import io.aipaas.cluster.agent.rbac.audit.BindingAuditEvent;
import io.aipaas.cluster.agent.rbac.autoconfigure.ClusterAgentRbacProperties;
import io.aipaas.cluster.agent.rbac.internal.BindingManifestRenderer.RenderedManifest;
import io.aipaas.cluster.agent.rbac.port.BindingApplyClient;
import io.aipaas.cluster.agent.rbac.port.BindingAuditSink;
import io.aipaas.cluster.agent.rbac.template.BindingTemplate;
import io.aipaas.cluster.agent.runtime.KubeResourcePage;
import io.aipaas.cluster.agent.runtime.KubeResourceService;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

/**
 * Layer 1 의 {@link KubeResourceService} 를 사용해 cluster 안 ClusterRoleBinding / RoleBinding apply.
 *
 * <p>본 default 구현은 stateless — backend table 또는 자체 cache 없음.
 */
@Slf4j
public class AgentBindingApplyClient implements BindingApplyClient {

	private static final String CRB_KIND = "ClusterRoleBinding";
	private static final String RB_KIND = "RoleBinding";

	private final KubeResourceService kubeService;
	private final BindingAuditSink auditSink;
	private final BindingManifestRenderer renderer;
	private final ClusterAgentRbacProperties.Labels labels;

	public AgentBindingApplyClient(KubeResourceService kubeService, BindingAuditSink auditSink,
			ClusterAgentRbacProperties props) {
		this.kubeService = kubeService;
		this.auditSink = auditSink;
		this.labels = props.labels();
		this.renderer = new BindingManifestRenderer(props.labels());
	}

	@Override
	public ApplyResult apply(String clusterName, BindingTemplate template, String resolvedGroup, String actor) {
		auditSink.record(BindingAuditEvent.attempt(actor, clusterName, template.id(), resolvedGroup,
				"manifest-apply"));

		List<RenderedManifest> manifests = renderer.render(template, resolvedGroup, null);
		if (manifests.isEmpty()) {
			auditSink.record(BindingAuditEvent.rejected(actor, clusterName, template.id(), resolvedGroup,
					"roleRefs empty — manifest 생성 불가"));
			throw new IllegalArgumentException("template[" + template.id() + "] roleRefs 비어 있음");
		}

		RenderedManifest primary = manifests.get(0);
		for (RenderedManifest m : manifests) {
			JsonNode result = kubeService.applyResource(clusterName, m.namespace(), m.yaml(), false);
			log.debug("Applied binding {} (ns={}) version={}", m.name(), m.namespace(),
					extractResourceVersion(result));
		}
		auditSink.record(BindingAuditEvent.applied(actor, clusterName, template.id(), resolvedGroup,
				primary.name()));
		return new ApplyResult(primary.name(), primary.namespace());
	}

	@Override
	public void deleteByTemplate(String clusterName, String templateId, String actor) {
		deleteByLabel(clusterName, labels.templateKey() + "=" + templateId, actor, templateId, "by-template");
	}

	@Override
	public void deleteByAddon(String clusterName, String addonName, String actor) {
		deleteByLabel(clusterName, labels.addonKey() + "=" + addonName, actor, addonName, "by-addon");
	}

	private void deleteByLabel(String clusterName, String labelSelector, String actor, String contextId,
			String reason) {
		for (NameNamespace nn : listAllMatching(clusterName, CRB_KIND, labelSelector, true)) {
			boolean ok = kubeService.deleteResource(clusterName, "", CRB_KIND, nn.name());
			if (ok) auditSink.record(BindingAuditEvent.deleted(actor, clusterName, contextId, nn.name(), reason));
		}
		for (NameNamespace nn : listAllMatching(clusterName, RB_KIND, labelSelector, false)) {
			boolean ok = kubeService.deleteResource(clusterName, nn.namespace(), RB_KIND, nn.name());
			if (ok) auditSink.record(BindingAuditEvent.deleted(actor, clusterName, contextId, nn.name(), reason));
		}
	}

	private List<NameNamespace> listAllMatching(String clusterName, String kind, String labelSelector,
			boolean clusterScoped) {
		List<NameNamespace> all = new ArrayList<>();
		String token = null;
		String ns = clusterScoped ? "" : "";
		do {
			KubeResourcePage page = kubeService.listResourcesPaginated(clusterName, ns, kind, 500, token,
					labelSelector);
			if (page == null || page.items() == null || !page.items().isArray()) break;
			for (JsonNode item : page.items()) {
				String name = item.path("metadata").path("name").asText("");
				String itemNs = item.path("metadata").path("namespace").asText("");
				if (!name.isEmpty()) all.add(new NameNamespace(name, itemNs));
			}
			token = page.continueToken();
		} while (token != null && !token.isBlank());
		return all;
	}

	private static String extractResourceVersion(JsonNode result) {
		if (result == null) return "";
		return result.path("metadata").path("resourceVersion").asText("");
	}

	private record NameNamespace(String name, String namespace) {}
}
