package io.aipaas.cluster.agent.rbac.internal;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.aipaas.cluster.agent.rbac.port.BindingTemplateCatalog;
import io.aipaas.cluster.agent.rbac.template.BindingTemplate;
import io.aipaas.cluster.agent.rbac.template.OidcGroupSelector;
import io.aipaas.cluster.agent.rbac.template.RoleRef;
import io.aipaas.cluster.agent.rbac.template.TargetSubject;
import io.aipaas.cluster.agent.rbac.template.TieredBindingTemplate;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

/**
 * Classpath resource (default {@code binding-templates.yaml}) 에서 template 읽기.
 *
 * <p>YAML schema (tieredRoleRefs default form + advanced forClusters form 둘 다 지원):
 * <pre>
 * templates:
 *   - id: team-x
 *     oidcGroupSelector: { matchExact: [team-x] }
 *     tieredRoleRefs:
 *       tierLabel: anycloud.io/tier
 *       tiers:
 *         prod: [{ kind: ClusterRole, name: view }]
 *         dev:  [{ kind: ClusterRole, name: admin }]
 *
 *   - id: ops-fleet-admin
 *     oidcGroupSelector: { matchExact: [ops-team] }
 *     forClusters: { matchLabels: {} }
 *     roleRefs: [{ kind: ClusterRole, name: admin }]
 * </pre>
 *
 * <p>읽기 1회 + in-memory 저장 — runtime reload 미지원 (host 가 필요하면 별도 watcher SPI 으로 교체).
 */
@Slf4j
public class ClasspathBindingTemplateCatalog implements BindingTemplateCatalog {

	private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

	private final List<BindingTemplate> templates;

	public ClasspathBindingTemplateCatalog(String classpathResource) {
		this.templates = loadFromClasspath(classpathResource);
		log.info("Loaded {} binding template(s) from classpath:{}", templates.size(), classpathResource);
	}

	@Override
	public List<BindingTemplate> list() {
		return Collections.unmodifiableList(templates);
	}

	@Override
	public List<BindingTemplate> resolveFor(Map<String, String> clusterLabels) {
		return templates.stream()
				.filter(t -> t.forClusters().matches(clusterLabels))
				.toList();
	}

	private static List<BindingTemplate> loadFromClasspath(String path) {
		Resource resource = new ClassPathResource(path);
		if (!resource.exists()) {
			log.warn("Binding templates 파일 없음 (classpath:{}) — 빈 catalog 로 동작", path);
			return List.of();
		}
		try (InputStream in = resource.getInputStream()) {
			CatalogRoot root = YAML.readValue(in, CatalogRoot.class);
			if (root == null || root.templates == null) return List.of();

			List<BindingTemplate> all = new ArrayList<>();
			for (TemplateNode node : root.templates) {
				all.addAll(node.toBindingTemplates());
			}
			return List.copyOf(all);
		} catch (IOException e) {
			throw new IllegalStateException(
					"binding-templates parse 실패 (classpath:" + path + "): " + e.getMessage(), e);
		}
	}

	// ---- YAML schema 매핑 ----

	private static class CatalogRoot {
		public List<TemplateNode> templates;
	}

	private static class TemplateNode {
		public String id;
		public OidcGroupSelector oidcGroupSelector;
		public List<TargetSubject> targetSubjects;
		public TieredRefsNode tieredRoleRefs;
		public ForClustersNode forClusters;
		public List<RoleRef> roleRefs;

		List<BindingTemplate> toBindingTemplates() {
			if (id == null || id.isBlank()) {
				throw new IllegalArgumentException("template.id 필수");
			}
			if (oidcGroupSelector == null) {
				throw new IllegalArgumentException("template[" + id + "].oidcGroupSelector 필수");
			}

			if (tieredRoleRefs != null) {
				TieredBindingTemplate tiered = new TieredBindingTemplate(
						id, oidcGroupSelector, targetSubjects,
						tieredRoleRefs.tierLabel,
						tieredRoleRefs.tiers);
				return tiered.expand();
			}

			// advanced form: forClusters + roleRefs
			io.aipaas.cluster.agent.rbac.template.LabelSelector selector =
					new io.aipaas.cluster.agent.rbac.template.LabelSelector(
							forClusters == null ? null : forClusters.matchLabels);
			return List.of(new BindingTemplate(id, selector, oidcGroupSelector, targetSubjects, roleRefs));
		}
	}

	private static class TieredRefsNode {
		public String tierLabel;
		public Map<String, List<RoleRef>> tiers;
	}

	private static class ForClustersNode {
		public Map<String, String> matchLabels;
	}

	// jackson 의 unused 경고 회피 위해 TypeReference 한 번 참조.
	@SuppressWarnings("unused")
	private static final TypeReference<List<BindingTemplate>> UNUSED_REF = new TypeReference<>() {};
}
