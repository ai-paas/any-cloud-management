# cluster-agent-features-spring-boot-starter

Layer 2 통합 starter — Layer 1 (`cluster-agent-spring-boot-starter`) 의 reverse-tunnel + 명령
dispatch 위에 **RBAC / Backup / Observability** 3 feature 를 한 artifact 으로 묶음.

## 통합 배경

3 feature 가 모두 Layer 1 (`cluster-agent-spring-boot-starter`) 의존. 별도 starter 로 분리하면:

- 한 consumer 가 3 starter 모두 사용 시 cluster-agent 가 `api` transitive 로 3중 pull
- 5 starter 의 maintenance 비용 (5 build.gradle / 5 changelog / 5 README) vs 단일 consumer
  (anycloud) 환경

→ 단일 artifact 으로 통합하여 abstraction tax 제거. sub-package 구조 (`rbac/`, `backup/`,
`observability/`) 는 보존 — 향후 외부 consumer 가 등장하면 분리 비용 낮음.

## Sub-feature 지도

각 sub-feature 의 상세 설계는 별도 문서:

| Sub-feature | 책임 | 상세 |
|---|---|---|
| **RBAC** | OIDC group → K8s ClusterRoleBinding orchestration. Caffeine TTL fleet view + informer push event. | [cluster-agent-rbac-starter.md](./cluster-agent-rbac-starter.md) |
| **Backup** | Velero install + schedule. etcd snapshot + PKI backup (control-plane 노드 1대 host network). | [cluster-agent-backup-starter.md](./cluster-agent-backup-starter.md) |
| **Observability** | PromQL passthrough. alert rule 설치 (in-cluster Prometheus operator). Grafana dashboard URL 조회. | [cluster-agent-observability-starter.md](./cluster-agent-observability-starter.md) |

## AutoConfiguration

단일 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`:

```
io.aipaas.cluster.agent.rbac.autoconfigure.ClusterAgentRbacAutoConfiguration
io.aipaas.cluster.agent.backup.autoconfigure.ClusterAgentBackupAutoConfiguration
io.aipaas.cluster.agent.observability.autoconfigure.ClusterObservabilityAutoConfiguration
```

3 AutoConfiguration class 각자 `@ConditionalOnClass` / `@ConditionalOnBean` 으로 활성 조건 제어 —
한 starter 안에서 공존하지만 host 가 일부만 활성화 가능.

## Maven 좌표

```gradle
implementation 'io.aipaas.cluster:cluster-agent-features-spring-boot-starter:0.1.0'
```

호환을 위한 transitive 의존:
```
io.aipaas.cluster:cluster-agent-spring-boot-starter:0.1.0   (api)
com.github.ben-manes.caffeine:caffeine:3.1.8                (api)
com.fasterxml.jackson.dataformat:jackson-dataformat-yaml    (api)
org.yaml:snakeyaml:2.3                                       (implementation)
```

## 호스트 SPI 요약

| Feature | SPI port | default impl | host 책임 시점 |
|---|---|---|---|
| RBAC | `BindingTemplateCatalog` | `ClasspathBindingTemplateCatalog` (binding-templates.yaml) | template 외부에서 가져오려면 override |
| RBAC | `BindingApplyClient` | `AgentBindingApplyClient` (agent gRPC) | 보통 default |
| RBAC | `BindingFleetView` | `SimpleBindingFleetView` (Caffeine TTL + informer push) | 보통 default |
| RBAC | `BindingAuditSink` | `LoggingBindingAuditSink` (SLF4J) | 감사 DB 연동 시 override |
| Backup | `BackupHistoryWriter` | host 책임 (no default) | 항상 host 가 JPA impl 제공 |
| Observability | `ClusterCatalog` | host 책임 (no default) | cluster registry 가 host 의 source of truth |
| Observability | `ClusterCapabilitiesSink` | host 책임 (no default) | capability metric 영구화 |

각 SPI 의 상세 contract 는 sub-feature 문서 참조.

## 분리 trigger

다음 시점에 sub-feature 별 starter 분리 검토:

- 외부 consumer 가 한 sub-feature 만 사용하고자 함 (예: RBAC 만)
- 한 sub-feature 만 별도 lifecycle 로 release 필요 (semver 충돌)
- 한 sub-feature 의 dependency footprint 가 다른 sub-feature 와 충돌

위 trigger 없으면 통합 유지가 권장. sub-package 가 명확히 분리되어 있어 분리 비용은 ~6h 수준
(이전 분리 이력 기반).
