package io.aipaas.cluster.agent.rbac.template;

/**
 * K8s ClusterRoleBinding subject. {@code name} 에 {@code $oidcGroup} 같은 placeholder 지원 — apply
 * 시점에 selector 의 매칭 group 으로 치환.
 */
public record TargetSubject(
		Kind kind,
		String name,
		String namespace) {

	public TargetSubject {
		if (kind == null) kind = Kind.Group;
	}

	public enum Kind {
		Group,
		User,
		ServiceAccount
	}
}
