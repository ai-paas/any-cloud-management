package io.aipaas.cluster.agent.rbac.template;

import java.util.List;

/** OIDC group/user 선택자. */
public record OidcGroupSelector(Kind kind, List<String> matchExact) {

    public OidcGroupSelector {
        if (kind == null) kind = Kind.Group;
        if (matchExact == null) matchExact = List.of();
    }

    public enum Kind {
        Group,
        User
    }
}
