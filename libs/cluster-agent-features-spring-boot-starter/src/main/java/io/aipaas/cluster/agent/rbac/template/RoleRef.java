package io.aipaas.cluster.agent.rbac.template;

import java.util.List;

/** K8s ClusterRole / Role reference. */
public record RoleRef(Kind kind, String name, Scope scope, List<String> namespaces) {

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
