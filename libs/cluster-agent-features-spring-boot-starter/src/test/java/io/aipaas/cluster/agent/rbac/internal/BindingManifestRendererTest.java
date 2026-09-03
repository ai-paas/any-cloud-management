package io.aipaas.cluster.agent.rbac.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.aipaas.cluster.agent.rbac.autoconfigure.ClusterAgentRbacProperties;
import io.aipaas.cluster.agent.rbac.internal.BindingManifestRenderer.RenderedManifest;
import io.aipaas.cluster.agent.rbac.template.BindingTemplate;
import io.aipaas.cluster.agent.rbac.template.LabelSelector;
import io.aipaas.cluster.agent.rbac.template.OidcGroupSelector;
import io.aipaas.cluster.agent.rbac.template.RoleRef;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BindingManifestRendererTest {

	private final BindingManifestRenderer renderer =
			new BindingManifestRenderer(new ClusterAgentRbacProperties.Labels(null, null));

	@Test
	void clusterScope_rendersClusterRoleBinding() {
		var template = new BindingTemplate("team-x@prod",
				new LabelSelector(Map.of("anycloud.io/tier", "prod")),
				new OidcGroupSelector(OidcGroupSelector.Kind.Group, List.of("team-x")),
				null,
				List.of(new RoleRef(RoleRef.Kind.ClusterRole, "view", RoleRef.Scope.ClusterScope, null)));

		List<RenderedManifest> manifests = renderer.render(template, "team-x", null);

		assertThat(manifests).hasSize(1);
		RenderedManifest m = manifests.get(0);
		assertThat(m.namespace()).isEmpty();
		assertThat(m.name()).startsWith("aipaas-team-x-prod");
		assertThat(m.yaml())
				.contains("kind: ClusterRoleBinding")
				.contains("aipaas.io/managed-by: \"anycloud\"")
				.contains("aipaas.io/template: \"team-x@prod\"")
				.contains("aipaas.io/oidc-group: \"team-x\"")
				.contains("name: \"team-x\"")  // subject
				.contains("name: view");       // roleRef
	}

	@Test
	void namespacedScope_rendersOneRoleBindingPerNamespace() {
		var template = new BindingTemplate("team-x@dev",
				new LabelSelector(Map.of()),
				new OidcGroupSelector(null, List.of("team-x")),
				null,
				List.of(new RoleRef(RoleRef.Kind.ClusterRole, "edit", RoleRef.Scope.Namespaced,
						List.of("ns-a", "ns-b"))));

		List<RenderedManifest> manifests = renderer.render(template, "team-x", null);

		assertThat(manifests).hasSize(2);
		assertThat(manifests).extracting(RenderedManifest::namespace)
				.containsExactly("ns-a", "ns-b");
		assertThat(manifests.get(0).yaml()).contains("kind: RoleBinding").contains("namespace: ns-a");
	}

	@Test
	void addonLabel_appearsWhenProvided() {
		var template = new BindingTemplate("monitoring-view", null,
				new OidcGroupSelector(null, List.of("dev-team")), null,
				List.of(new RoleRef(RoleRef.Kind.ClusterRole, "view", null, null)));

		String yaml = renderer.render(template, "dev-team", "monitoring").get(0).yaml();

		assertThat(yaml).contains("aipaas.io/addon: \"monitoring\"");
	}

	@Test
	void sanitizesName_replacesInvalidChars() {
		var template = new BindingTemplate("Team_X/Prod", null,
				new OidcGroupSelector(null, List.of("g")), null,
				List.of(new RoleRef(RoleRef.Kind.ClusterRole, "view", null, null)));

		String name = renderer.render(template, "user@example.com", null).get(0).name();

		assertThat(name).matches("[a-z0-9-]+");
	}
}
