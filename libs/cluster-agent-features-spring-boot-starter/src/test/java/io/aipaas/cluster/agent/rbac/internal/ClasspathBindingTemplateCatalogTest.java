package io.aipaas.cluster.agent.rbac.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.aipaas.cluster.agent.rbac.template.BindingTemplate;
import io.aipaas.cluster.agent.rbac.template.RoleRef;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ClasspathBindingTemplateCatalogTest {

	@Test
	void loadsDefaultEmptyTemplates() {
		var catalog = new ClasspathBindingTemplateCatalog("binding-templates.yaml");
		assertThat(catalog.list()).isEmpty();
	}

	@Test
	void loadsTieredAndAdvancedForms() {
		var catalog = new ClasspathBindingTemplateCatalog("test-templates-mixed.yaml");
		List<BindingTemplate> templates = catalog.list();

		// tiered "team-x" → 2개 expanded (prod + dev)
		assertThat(templates).extracting(BindingTemplate::id)
				.contains("team-x@prod", "team-x@dev", "ops-fleet-admin");

		BindingTemplate prod = templates.stream().filter(t -> t.id().equals("team-x@prod")).findFirst().orElseThrow();
		assertThat(prod.roleRefs())
				.singleElement()
				.satisfies(r -> {
					assertThat(r.kind()).isEqualTo(RoleRef.Kind.ClusterRole);
					assertThat(r.name()).isEqualTo("view");
				});
		assertThat(prod.forClusters().matchLabels()).containsEntry("anycloud.io/tier", "prod");
	}

	@Test
	void resolveFor_filtersByClusterLabels() {
		var catalog = new ClasspathBindingTemplateCatalog("test-templates-mixed.yaml");

		List<BindingTemplate> forProd = catalog.resolveFor(Map.of("anycloud.io/tier", "prod"));
		assertThat(forProd).extracting(BindingTemplate::id).contains("team-x@prod", "ops-fleet-admin");
		assertThat(forProd).extracting(BindingTemplate::id).doesNotContain("team-x@dev");
	}

	@Test
	void missingFile_returnsEmptyCatalog() {
		var catalog = new ClasspathBindingTemplateCatalog("does-not-exist.yaml");
		assertThat(catalog.list()).isEmpty();
	}
}
