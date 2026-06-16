-- D-3c — oidc_group_binding table 제거.
-- RBAC binding 자체는 K8s ClusterRoleBinding 에 영구화 (starter 의 K8s + Keycloak 가 truth).
-- backend DB 의 oidc_group_binding entity 는 더 이상 책임 영역 아님.
-- starter (cluster-agent-rbac-spring-boot-starter) 의 binding-templates.yaml 이 catalog,
-- ClusterPolicyBootstrapper.applyStarterBindings 가 fan-out path.
DROP TABLE IF EXISTS oidc_group_binding;
