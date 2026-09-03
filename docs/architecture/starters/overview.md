# Spring Boot Starters — Layer 모델 + DB-free 가정

anycloud 프로젝트의 `libs/cluster-*-spring-boot-starter` 모듈은 backend 외부에서도 재배포
가능한 self-contained 패키지입니다. anycloud backend 가 첫 호스트지만, 다른 SaaS / on-prem
운영팀이 같은 starter 만 import 해서 동일 기능을 즉시 활용할 수 있는 것이 목표.

## Layer 모델

```
cluster-agent-spring-boot-starter (Layer 1, required)
  - reverse-tunnel gRPC, JWT identity, allowlist enforcement
  - SPI: AgentIdentityStore, IdempotencyStore, SigningKeyResolver
        ↓
        └─ cluster-agent-features-spring-boot-starter (Layer 2 통합)
              sub-package:
                - rbac/          : OIDC group → ClusterRoleBinding
                - backup/        : Velero / etcd snapshot / PKI
                - observability/ : Prometheus passthrough + alert + dashboard
              3 AutoConfiguration 각자 @ConditionalOnClass / @ConditionalOnBean 으로 활성 제어

cluster-provisioning-spring-boot-starter (별도 lifecycle, cluster-agent 의존 X)
  - Pulumi CLI 위임 multi-CSP VM 인프라 orchestration
```

Layer 1 이 transport + identity 만 책임. Layer 2 통합 starter 안의 3 sub-feature 각자 host SPI
override 가능. cluster-provisioning 은 cluster-agent 와 의존 0 의 별개 lifecycle.

## DB-free 가정

Layer 1 + Layer 2 starter 들은 **데이터베이스 없이 동작**하도록 설계.

| Starter | DB 의존 SPI | InMemory/Stateless default | DB-free 가능? |
|---|---|---|---|
| cluster-agent-* (Layer 1) | AgentIdentityStore, IdempotencyStore, SigningKeyResolver | InMemory* + PropertySigningKeyResolver | ✅ |
| cluster-agent-backup-* | BackupHistoryWriter | NoOp | ✅ |
| cluster-agent-observability-* | (storage SPI 없음 — cluster 안 Prometheus 가 truth) | — | ✅ |
| cluster-agent-rbac-* | BindingTemplateCatalog, BindingAuditSink | Classpath / SLF4J | ✅ |
| cluster-provisioning-* | ClusterDescriptorRepository, Pulumi state backend | InMemory (test only) | ⚠️ Pulumi state 가 외부 storage 필수 (RustFS/S3) |

→ anycloud backend 는 자체 RDB 를 보유 (cluster registry, CSP credential, audit log 등). 다만
**RBAC binding 과 사용자/그룹은 DB 에 두지 않음** (다음 섹션).

## State 의 책임 분리

| 데이터 | 저장 위치 | Truth |
|---|---|---|
| 사용자 identity | Keycloak (OIDC IdP) | Keycloak |
| 그룹 / role membership | Keycloak | Keycloak |
| RBAC 정책 (group → ClusterRole) | K8s 의 ClusterRoleBinding | K8s |
| Addon → ClusterRole mapping | addon helm chart 가 동봉한 ClusterRole | K8s |
| 운영자 catalog (binding 추천) | starter classpath resource (binding-templates.yaml) | starter |
| Cluster registry (kubeconfig, endpoint) | anycloud backend DB | backend (외부 storage 없음) |
| Pulumi descriptors | anycloud backend DB | backend (Pulumi state 의 메타데이터) |
| CSP credentials (encrypted) | anycloud backend DB | backend |
| Workflow audit log | anycloud backend DB + RabbitMQ | backend |
| LRO (long-running operation) state | anycloud backend DB | backend |
| Binding audit log | SLF4J / 외부 SIEM (BindingAuditSink SPI) | host 선택 |

이 모델의 결과:
- starter 외부 재배포 가능 (다른 SaaS 가 자체 RDB 없이도 starter 활성화)
- K8s 가 binding truth → drift 없음, GitOps 호환
- backend 의 책임 좁아짐 (RBAC 와 identity 는 자기 책임 아님)

## Starter 별 책임 요약

### cluster-agent-spring-boot-starter (Layer 1)

| 책임 | API |
|---|---|
| gRPC server (Stream / PodExec) | `AgentRuntimeEndpoint` |
| JWT identity 검증 | `AgentIdentityStore` SPI |
| 명령 dispatch + 결과 future | `AgentCommandRouter` |
| K8s 자원 query/mutate via agent | `KubeResourceService` |
| Helm 명령 via agent | `HelmReleaseService` |
| Pod log streaming | `PodLogStreamService` |
| Impersonation pass-through | `ImpersonationContext` |
| AllowList enforcement | agent 측 ConfigMap watch |

### cluster-agent-features-spring-boot-starter — backup 서브패키지

| 책임 | API |
|---|---|
| etcd snapshot | `EtcdBackupService` (RPC: `BACKUP_ETCD`) |
| PKI tar | `PkiBackupService` (RPC: `BACKUP_PKI`) |
| Velero install | `VeleroInstaller` |
| Velero Backup/Restore/Schedule CR | `VeleroBackupService` / `Restore` / `Schedule` |

K8s minor version upgrade 는 **scope 외** (별도 운영 도구 — `docs/runbooks/cluster-upgrade.md`).

### cluster-agent-features-spring-boot-starter — observability 서브패키지

| 책임 | API |
|---|---|
| PromQL 쿼리 fan-out | `ObservabilityQueryService` |
| kube-prometheus-stack install | `AlertRuleInstaller` |
| Grafana ingress URL 발견 | `DashboardLocator` |
| 표준 메트릭 헬퍼 | `ClusterMetricsService` |

### cluster-agent-features-spring-boot-starter — rbac 서브패키지

| 책임 | API |
|---|---|
| Binding template catalog | `BindingTemplateCatalog` SPI + classpath default |
| ClusterRoleBinding apply/delete via agent | `BindingApplyClient` SPI + agent-gRPC default |
| Fleet view (적용된 binding 목록) | `BindingFleetView` SPI + Caffeine cache default |
| Audit | `BindingAuditSink` SPI + SLF4J default |
| Addon catalog 통합 (D-5) | `AddonRbacTemplateMapper` (host 측, addon → BindingTemplate 변환) |

### cluster-provisioning-spring-boot-starter

| 책임 | API |
|---|---|
| Pulumi CLI orchestration | `PulumiCommandService` |
| 8 CSP provider modules | `infra/pulumi` Go 코드 (별도 binary) |
| stack-config 영속화 | `ClusterDescriptorRepository` SPI (host = backend DB) |

> 본 starter 만 외부 storage (RustFS/S3) 필수 — Pulumi state 가 외부에서만 의미.

## 호스트가 자체 application 으로 starter 활용 (예시)

```kotlin
// build.gradle.kts
dependencies {
    implementation("io.aipaas.cluster:cluster-agent-spring-boot-starter:0.1.0")
    implementation("io.aipaas.cluster:cluster-agent-features-spring-boot-starter:0.1.0")
}
```

```yaml
# application.yaml — DB 없이도 동작
cluster-agent:
  jwt:
    secret: ${AGENT_JWT_SECRET}
cluster-rbac:
  templates:
    classpath-resource: binding-templates.yaml
```

```yaml
# binding-templates.yaml (classpath)
templates:
  - id: dev-team-admin
    oidcGroupSelector: { matchExact: [dev-team] }
    forClusters: { matchLabels: {} }
    roleRefs:
      - { kind: ClusterRole, name: admin, scope: ClusterScope }
```

```kotlin
// 호스트 SpringBootApplication — Cluster registry SPI 만 채우면 Layer 2 자동 활성
@SpringBootApplication
class MyClusterManagerApp {
    @Bean
    fun clusterCatalog(): ClusterCatalog = MyInMemoryClusterRegistry()
}
```

→ host 가 자체 RDB 가 없어도 starter 만으로 RBAC orchestration 가능. anycloud backend 는 이 위에
자체 cluster registry / Pulumi / CSP credential storage 추가한 host application.

## 참조

- [`oidc-binding-multi-idp.md`](../identity/oidc-binding-multi-idp.md) — RBAC binding 설계
- [`dynamic-addon-rbac.md`](../identity/dynamic-addon-rbac.md) — addon catalog 기반 ClusterRoleBinding 자동 적용
- [`persistence-layer.md`](../persistence-layer.md) — backend DB schema 의 책임 범위
- [`../../conventions/folder-structure.md`](../../conventions/folder-structure.md) — `domain/{X}/` 패키지 컨벤션
