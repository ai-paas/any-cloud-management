package io.aipaas.cluster.agent.rbac.internal;

import io.aipaas.cluster.agent.rbac.autoconfigure.ClusterAgentRbacProperties;
import io.aipaas.cluster.agent.rbac.template.BindingTemplate;
import io.aipaas.cluster.agent.rbac.template.RoleRef;
import io.aipaas.cluster.agent.rbac.template.TargetSubject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * BindingTemplate → K8s ClusterRoleBinding / RoleBinding manifest (YAML) 렌더.
 *
 * <p>label 규약: managed-by / template / oidc-group / addon (addon hook 에서 주입). 이름 규칙:
 * {@code aipaas-<templateId>-<sanitizedGroup>} — K8s DNS subdomain (RFC 1123) 호환.
 *
 * <p>{@code targetSubjects} 의 placeholder {@code $oidcGroup} 은 resolvedGroup 으로 치환.
 */
class BindingManifestRenderer {

	private static final Pattern UNSAFE = Pattern.compile("[^a-z0-9-]+");
	private static final int MAX_NAME = 253;
	private static final String OIDC_PLACEHOLDER = "$oidcGroup";

	private final ClusterAgentRbacProperties.Labels labels;

	BindingManifestRenderer(ClusterAgentRbacProperties.Labels labels) {
		this.labels = labels;
	}

	/**
	 * scope 별 manifest 생성. ClusterScope → 단일 ClusterRoleBinding. Namespaced →
	 * {@code roleRef.namespaces} 별 RoleBinding 다수.
	 *
	 * @param addonNameOverride null 이면 template.addonId() 사용. caller 가 명시 override 가능.
	 */
	List<RenderedManifest> render(BindingTemplate template, String resolvedGroup, String addonNameOverride) {
		List<RenderedManifest> out = new ArrayList<>();
		String baseName = sanitizeName("aipaas-" + template.id() + "-" + resolvedGroup);
		Map<String, String> commonLabels = new LinkedHashMap<>();
		commonLabels.put(labels.managedByKey(), labels.managedBy());
		commonLabels.put(labels.templateKey(), template.id());
		commonLabels.put(labels.oidcGroupKey(), resolvedGroup);
		String addonName = addonNameOverride != null ? addonNameOverride : template.addonId();
		if (addonName != null && !addonName.isBlank()) {
			commonLabels.put(labels.addonKey(), addonName);
		}

		List<TargetSubject> subjects = resolveSubjects(template, resolvedGroup);

		for (RoleRef roleRef : template.roleRefs()) {
			if (roleRef.scope() == RoleRef.Scope.ClusterScope) {
				out.add(renderClusterRoleBinding(baseName, commonLabels, subjects, roleRef));
			} else {
				for (String ns : roleRef.namespaces()) {
					out.add(renderRoleBinding(baseName, ns, commonLabels, subjects, roleRef));
				}
			}
		}
		return out;
	}

	private RenderedManifest renderClusterRoleBinding(String name, Map<String, String> commonLabels,
			List<TargetSubject> subjects, RoleRef roleRef) {
		StringBuilder sb = new StringBuilder();
		sb.append("apiVersion: rbac.authorization.k8s.io/v1\n");
		sb.append("kind: ClusterRoleBinding\n");
		sb.append("metadata:\n");
		sb.append("  name: ").append(name).append('\n');
		appendLabels(sb, commonLabels);
		appendSubjects(sb, subjects);
		appendRoleRef(sb, roleRef);
		return new RenderedManifest(name, "", sb.toString());
	}

	private RenderedManifest renderRoleBinding(String baseName, String namespace,
			Map<String, String> commonLabels, List<TargetSubject> subjects, RoleRef roleRef) {
		String name = sanitizeName(baseName + "-" + namespace);
		StringBuilder sb = new StringBuilder();
		sb.append("apiVersion: rbac.authorization.k8s.io/v1\n");
		sb.append("kind: RoleBinding\n");
		sb.append("metadata:\n");
		sb.append("  name: ").append(name).append('\n');
		sb.append("  namespace: ").append(namespace).append('\n');
		appendLabels(sb, commonLabels);
		appendSubjects(sb, subjects);
		appendRoleRef(sb, roleRef);
		return new RenderedManifest(name, namespace, sb.toString());
	}

	private static void appendLabels(StringBuilder sb, Map<String, String> labels) {
		sb.append("  labels:\n");
		labels.forEach((k, v) -> sb.append("    ").append(k).append(": \"").append(v).append("\"\n"));
	}

	private static void appendSubjects(StringBuilder sb, List<TargetSubject> subjects) {
		sb.append("subjects:\n");
		for (TargetSubject s : subjects) {
			sb.append("  - kind: ").append(s.kind().name()).append('\n');
			sb.append("    name: ").append(quote(s.name())).append('\n');
			sb.append("    apiGroup: rbac.authorization.k8s.io\n");
			if (s.kind() == TargetSubject.Kind.ServiceAccount && s.namespace() != null) {
				sb.append("    namespace: ").append(s.namespace()).append('\n');
			}
		}
	}

	private static void appendRoleRef(StringBuilder sb, RoleRef roleRef) {
		sb.append("roleRef:\n");
		sb.append("  apiGroup: rbac.authorization.k8s.io\n");
		sb.append("  kind: ").append(roleRef.kind().name()).append('\n');
		sb.append("  name: ").append(roleRef.name()).append('\n');
	}

	private static List<TargetSubject> resolveSubjects(BindingTemplate template, String resolvedGroup) {
		List<TargetSubject> subjects = template.targetSubjects();
		if (subjects == null || subjects.isEmpty()) {
			// default — selector kind 기반 단일 subject
			return List.of(new TargetSubject(
					template.oidcGroupSelector().kind() == io.aipaas.cluster.agent.rbac.template.OidcGroupSelector.Kind.User
							? TargetSubject.Kind.User
							: TargetSubject.Kind.Group,
					resolvedGroup,
					null));
		}
		// placeholder 치환
		List<TargetSubject> out = new ArrayList<>(subjects.size());
		for (TargetSubject s : subjects) {
			String name = OIDC_PLACEHOLDER.equals(s.name()) ? resolvedGroup : s.name();
			out.add(new TargetSubject(s.kind(), name, s.namespace()));
		}
		return out;
	}

	private static String quote(String s) {
		return "\"" + s.replace("\"", "\\\"") + "\"";
	}

	private static String sanitizeName(String raw) {
		String lower = raw.toLowerCase(Locale.ROOT);
		String safe = UNSAFE.matcher(lower).replaceAll("-");
		safe = safe.replaceAll("-+", "-").replaceAll("^-|-$", "");
		if (safe.length() > MAX_NAME) safe = safe.substring(0, MAX_NAME);
		return safe;
	}

	record RenderedManifest(String name, String namespace, String yaml) {}
}
