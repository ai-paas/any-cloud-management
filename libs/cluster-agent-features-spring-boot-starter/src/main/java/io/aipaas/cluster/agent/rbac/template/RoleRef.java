package io.aipaas.cluster.agent.rbac.template;

import java.util.List;

/**
 * K8s ClusterRole / Role reference.
 *
 * <p>{@link Scope#ClusterScope} → ClusterRoleBinding 생성, {@link Scope#Namespaced} →
 * {@code namespaces} 의 각 namespace 에 RoleBinding 생성.
 */
public record RoleRef(
		Kind kind,
		String name,
		Scope scope,
		List<String> namespaces) {

	public RoleRef {
		if (kind == null) kind = Kind.ClusterRole;
		if (scope == null) scope = Scope.ClusterScope;
		if (namespaces == null) namespaces = List.of();
	}

	public enum Kind {
		ClusterRole,
		Role
	}

	public enum Scope {
		ClusterScope,
		Namespaced
	}
}
