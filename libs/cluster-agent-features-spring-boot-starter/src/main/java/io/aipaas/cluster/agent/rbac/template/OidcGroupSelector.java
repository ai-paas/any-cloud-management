package io.aipaas.cluster.agent.rbac.template;

import java.util.List;

/**
 * OIDC group/user 선택자.
 *
 * <p>matchExact only — observation 기반 regex (matchExpression) 폐기. dynamic team 운영은 Keycloak
 * group hierarchy 또는 group attribute 활용 (Pattern-A/B in docs/architecture/design/
 * oidc-binding-multi-idp.md).
 */
public record OidcGroupSelector(
		Kind kind,
		List<String> matchExact) {

	public OidcGroupSelector {
		if (kind == null) kind = Kind.Group;
		if (matchExact == null) matchExact = List.of();
	}

	public enum Kind {
		Group,
		User
	}
}
