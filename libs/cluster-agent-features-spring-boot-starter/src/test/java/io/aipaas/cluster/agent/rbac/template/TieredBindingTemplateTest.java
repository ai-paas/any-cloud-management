package io.aipaas.cluster.agent.rbac.template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TieredBindingTemplateTest {

	@Test
	void expand_createsOneTemplatePerTier() {
		var selector = new OidcGroupSelector(OidcGroupSelector.Kind.Group, List.of("team-x"));
		var prodRoles = List.of(new RoleRef(RoleRef.Kind.ClusterRole, "view", RoleRef.Scope.ClusterScope, null));
		var devRoles = List.of(new RoleRef(RoleRef.Kind.ClusterRole, "admin", RoleRef.Scope.ClusterScope, null));

		var tiered = new TieredBindingTemplate("team-x", selector, null, null,
				Map.of("prod", prodRoles, "dev", devRoles));

		List<BindingTemplate> expanded = tiered.expand();

		assertThat(expanded).hasSize(2);
		assertThat(expanded).extracting(BindingTemplate::id)
				.containsExactlyInAnyOrder("team-x@prod", "team-x@dev");
		assertThat(expanded)
				.allSatisfy(t -> assertThat(t.forClusters().matchLabels()).containsKey("anycloud.io/tier"));
	}

	@Test
	void expand_usesCustomTierLabel() {
		var selector = new OidcGroupSelector(null, List.of("team-x"));
		var roles = List.of(new RoleRef(RoleRef.Kind.ClusterRole, "view", null, null));
		var tiered = new TieredBindingTemplate("t", selector, null, "env",
				Map.of("prod", roles));

		assertThat(tiered.expand().get(0).forClusters().matchLabels())
				.containsExactly(Map.entry("env", "prod"));
	}

	@Test
	void rejectsEmptyTiers() {
		var selector = new OidcGroupSelector(null, List.of("x"));
		assertThatThrownBy(() -> new TieredBindingTemplate("t", selector, null, null, Map.of()))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void rejectsNullSelector() {
		assertThatThrownBy(() -> new TieredBindingTemplate("t", null, null, null,
				Map.of("prod", List.of())))
				.isInstanceOf(IllegalArgumentException.class);
	}
}
