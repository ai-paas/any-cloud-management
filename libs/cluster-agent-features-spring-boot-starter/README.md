# cluster-agent-features-spring-boot-starter

Layer 2 통합 starter — Layer 1 (`cluster-agent-spring-boot-starter`) 위에 RBAC / Backup /
Observability 3 feature 를 한 artifact 으로 묶음.

## 통합 배경

3 feature 가 모두 Layer 1 (cluster-agent) 의존. 별도 starter 로 분리하면:
- 한 consumer 가 3 starter 모두 사용 시 cluster-agent 가 `api` transitive 로 3중 pull
- 5 starter 의 maintenance 비용 (5 build.gradle / 5 changelog / 5 README) vs 1 consumer (anycloud)

→ 통합으로 abstraction tax 제거.

분리하면 좋은 case (rare):
- 향후 RBAC 만 사용하는 외부 consumer 등장
- backup 가 별도 lifecycle 로 release 필요

이 경우 sub-package 구조 (`rbac/`, `backup/`, `observability/`) 가 보존되어 있어 분리 비용 낮음.

## Sub-package 지도

| sub-package | 책임 | catalog |
|---|---|---|
| `rbac/` | OIDC group → K8s ClusterRoleBinding orchestration | `binding-templates.yaml` |
| `backup/` | Velero / etcd snapshot / PKI backup | `velero-policies/*.yaml` |
| `observability/` | PromQL passthrough + alert rule + Grafana dashboard | `alert-rules/*.yaml` |

## AutoConfiguration

`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`:

```
io.aipaas.cluster.agent.rbac.autoconfigure.ClusterAgentRbacAutoConfiguration
io.aipaas.cluster.agent.backup.autoconfigure.ClusterAgentBackupAutoConfiguration
io.aipaas.cluster.agent.observability.autoconfigure.ClusterObservabilityAutoConfiguration
```

3 AutoConfiguration class 는 각자 `@ConditionalOnClass` / `@ConditionalOnBean` 으로 활성 조건
제어 — 한 starter 안에서 공존하지만 host 가 일부만 활성화할 수 있음.

## 호스트 SPI

| Feature | SPI port | default impl |
|---|---|---|
| RBAC | `BindingTemplateCatalog` | `ClasspathBindingTemplateCatalog` (binding-templates.yaml) |
| RBAC | `BindingApplyClient` | `AgentBindingApplyClient` (agent gRPC) |
| RBAC | `BindingFleetView` | `SimpleBindingFleetView` (Caffeine TTL + informer push) |
| RBAC | `BindingAuditSink` | `LoggingBindingAuditSink` (SLF4J) |
| Backup | `BackupHistoryWriter` | host 가 JPA impl 제공 (default 없음) |
| Observability | `ClusterCatalog` | host 가 cluster registry 제공 |
| Observability | `ClusterCapabilitiesSink` | host 가 capability persist 제공 |
