# Architecture Overview

anycloud monorepo 의 component / package / 의존 관계를 정리했습니다. 자세한 API 목록은
[`api-inventory.md`](./api-inventory.md), 주요 flow 는 [`feature-flows.md`](./feature-flows.md) 를
참고합니다.

## 1. 전체 구성

```
┌────────────────────────────────────────────────────────────────┐
│                     User / Operator                            │
│            (web UI, kubectl, curl, helm, postman)              │
└─────────────────────┬──────────────────────────────────────────┘
                      │ REST / SSE
┌─────────────────────▼──────────────────────────────────────────┐
│            anycloud Spring Boot Backend (Java 21)              │
│  ┌────────────────────────────────────────────────────────┐    │
│  │  REST Controllers  (≈ 120 endpoints, 25 domains)       │    │
│  └───────────────────┬────────────────────────────────────┘    │
│                      │                                         │
│  ┌───────────────────▼────────────────────────────────────┐    │
│  │  Service Layer (cluster / agent / upgrade / audit /    │    │
│  │  vmoptions / credential / chart / observability ...)   │    │
│  └─────┬─────────────┬──────────────────┬──────────────────┘    │
│        │             │                  │                       │
│  ┌─────▼────┐   ┌────▼─────┐                                   │
│  │  JPA     │   │ gRPC     │                                   │
│  │  (Maria) │   │  Server  │                                   │
│  │  Flyway  │   │  9090    │                                   │
│  └──────────┘   └─────┬────┘                                   │
└───────────────────────│────────────────────────────────────────┘
                        │ gRPC (Bearer over TLS)
                        │                  │
                 ┌──────▼──────┐   ┌───────▼────────┐
                 │ cluster-    │   │  Target        │
                 │ agent (Go)  │◄──┤  Kubernetes    │
                 │ in target   │   │  cluster(s)    │
                 │ K8s cluster │   │                │
                 └─────────────┘   └────────────────┘
```

3-tier 구조 — **REST/UI (control plane)** → **Backend (Java)** → **agent (Go, in target cluster)**
가 핵심 축이며, **Pulumi provisioner** 와 **RabbitMQ workflow** 는 VM 기반 cluster 생성 시의 별도 경로입니다.

| Component | 위치 | 책임 |
|---|---|---|
| `apps/anycloud` | Spring Boot 3.5.16 / Java 21 | REST + gRPC server. JPA / Flyway. 핵심 control plane. |
| `apps/agent` | Go 1.26+ | target K8s cluster 내 sidecar/controller. bearer-over-TLS bidi stream 으로 backend 와 통신. |
| `libs/cluster-agent-spring-boot-starter` | Java starter (**Layer 1**) | gRPC schema (proto), starter SPI (runtime/identity/exec/logstream) — anycloud 와 모든 Layer 2/3 starter 가 의존. → [Extension guide](./starters/starter-extension-guide.md) |
| `libs/cluster-agent-features-spring-boot-starter` | Java starter (**Layer 2**) | RBAC, Backup, Observability 통합. v0.3.0 에서 observability / backup starter 를 흡수했다. |
| `libs/cluster-provisioning-spring-boot-starter` | Java starter | VM cluster provisioning. Pulumi **Java SDK** 기반이며 provider 별 `*Provisioner` 로 구현. v0.3.0 에서 Go 구현(`infra/pulumi`)을 대체했다. |
| `apps/agent/deploy/helm/cluster-agent` | Helm chart | target cluster 에 agent 배포 패키지. |

## 2. anycloud Backend (Java) 패키지 지도

`apps/anycloud/src/main/java/com/aipaas/anycloud/` 기준입니다. 최상위는 넷입니다.

| Package | 책임 |
|---|---|
| `domain/` | 도메인별 수직 분할. 각 도메인이 web, api, internal 을 자체 보유합니다 |
| `common/` | 에러 처리, 로깅, 검증, web 공통 (envelope, idempotency, audit interceptor) |
| `configuration/` | Spring 설정, 보안, 비동기 풀, 캐시, persistence |
| `grpc/` | starter 의 gRPC 서버를 띄우는 진입점 |

### 2.1 도메인 목록

| Domain | 책임 | 핵심 클래스 |
|---|---|---|
| `cluster` | cluster 등록, 조회, 연결 상태 | `ClusterFacadeImpl`, `ClusterServiceImpl`, `ClusterConnectivityServiceImpl` |
| `provisioning` | VM cluster state machine 과 워크플로 | `ClusterConvergenceOrchestratorImpl`, `VmClusterWorkflowSupportServiceImpl` |
| `agent` | agent 등록, 정책, fleet upgrade | `AgentBootstrapServiceImpl`, `AgentApiManagedInstaller`, `FleetUpgradeOrchestratorImpl` |
| `kube` | K8s 리소스 조회와 삭제. 전부 agent 경유 | `KubeServiceImpl`, `CachedKindResolver` |
| `chart` | Helm chart 카탈로그와 release lifecycle | `ChartServiceImpl`, `HelmCommandExecutor` |
| `helmrepo` | Helm repository CRUD 와 agent 동기화 | `HelmRepoServiceImpl`, `HelmRepoListSerializer` |
| `addon` | addon 설치 워크플로 | `AddonOrchestrator`, `*AddonInstaller` |
| `credential` | CSP 자격증명 암호화 보관 | `CspCredentialServiceImpl`, `CredentialHealthServiceImpl` |
| `vmoptions` | CSP 카탈로그 (region, spec, image) | `AbstractVmOptionsProvider` + CSP별 구현 |
| `operation` | long-running operation 추적 | `OperationServiceImpl` |
| `audit` | 감사 로그 | `AuditLogger`, `Audited` |
| `events` | 자원 변경 SSE | `ResourceEventBroker` |
| `webhook` | 외부 포털로 보내는 이벤트 | `WebhookPublisher` |

각 도메인은 `web/` (controller), `api/` (요청, 응답 DTO), `internal/` (구현) 으로 나뉩니다.
다른 도메인은 그 도메인의 root level service interface 만 지나갑니다. `internal/` 직접 참조는
ArchUnit 테스트가 막습니다.

### 2.2 Layering

controller 는 service 만 부릅니다. repository 를 직접 주입한 controller 가 몇 개 남아 있어
`ClusterController` 의 `stateHistoryRepository` 와 `FleetUpgradeController` 의 `runRepository`
가 그렇습니다. 새 코드는 service 를 거치세요.

## 3. JPA Entity 인벤토리

| Entity | Table | 용도 |
|---|---|---|
| `ClusterEntity` | `cluster` | 등록, 생성된 K8s cluster 메타 |
| `ClusterAgentEntity` | `cluster_agent` | agent instance 별 메타와 identity token hash |
| `VmClusterEntity` | `vm_cluster` | Pulumi 로 만든 VM cluster |
| `VmClusterStateHistoryEntity` | `vm_cluster_state_history` | state machine 이력 |
| `VmClusterComponentEntity` | `vm_cluster_component` | cluster 구성요소별 상태 |
| `OperationEntity` | `operation` | long-running operation |
| `AuditLogEntity` | `audit_log` | 감사 추적 |
| `CspCredentialEntity` | `csp_credential` | 암호화된 CSP 자격증명 |
| `HelmRepoEntity` | `helm_repo` | Helm repository 메타 |
| `ClusterAddonEntity` | `cluster_addon` | addon 설치 상태 |
| `WorkflowMessageLogEntity` | `workflow_message_log` | RabbitMQ 워크플로 이벤트 |
| `FleetUpgradeRunEntity` | `fleet_upgrade_run` | fleet upgrade 실행 |
| `AgentSigningKeyEntity` | `agent_signing_key` | registration token 서명 키 |
| `BootstrapJtiEntity` | `bootstrap_jti` | JWT JTI 재사용 방지 |
| `IdempotencyRecordEntity` | `idempotency_record` | Idempotency-Key 응답 캐시 (TTL 24시간) |
| `BackupHistoryEntity` | `backup_history` | 백업 이력 |

명시적 FK 가 거의 없습니다. cluster 를 지우면 `cluster_agent` 만 애플리케이션이 cascade 하고
나머지는 TTL sweeper 가 정리합니다.

## 4. Spring `@Configuration` 클래스

| Class | 위치 | 책임 | 노출 Bean |
|---|---|---|---|
| `WebSecurityConfig` | `configuration.security` | Static bearer token auth (toggle) | `SecurityFilterChain` |
| `WebSecurityDisabledConfig` | `configuration.security` | gateway-managed auth (permitAll) | `SecurityFilterChain` |
| `WebConfig` | `configuration.web` | CORS, header reg | — |
| `WebMvcInterceptorConfig` | `configuration.web` | 로깅 / req-resp wrap | `HandlerInterceptor` |
| `CacheConfig` | `configuration.persistence` | Spring Cache L2 | `CacheManager` |
| `ShedLockConfig` | `configuration.persistence` | 분산 잡 락 (sweeper) | `LockProvider` |
| `DevFlywayMigrationStrategy` | `configuration.persistence` | dev 모드 baseline skip | — |
| `BeanConfig` | `configuration.infrastructure` | RestTemplate / WebClient / YAML / ObjectMapper | (위 4개) |
| `OpenApiConfig` | `configuration.infrastructure` | springdoc swagger UI | docs groups |
| `BouncyCastleConfig` | `configuration.infrastructure` | JCE provider 등록 | — |
| `AsyncConfig` | `configuration.domain` | `@Async` thread pool | `Executor` |
| `AgentConfiguration` | `configuration.domain` | cluster-agent starter wiring | gRPC server beans |
| `RabbitMqVmClusterWorkflowConfiguration` | `configuration.domain` | RabbitMQ listener (있을 때만) | `SimpleMessageListenerContainer` |

## 5. SPI 경계 (interface + 다중 impl)

| Interface | impl | 활성화 조건 |
|---|---|---|
| `AuditLogger` | `InMemoryAuditLogger`, `DbAuditLogger` | `@ConditionalOnProperty` |
| `ClusterProvider` | `VmClusterProviderImpl`, `RegisteredClusterProviderImpl` | source type 기반 — registry pattern |
| `VmOptionsProvider` | AWS/GCP/Alibaba/OCI/OpenStack/Proxmox/IBM impl | provider name 기반 — registry |
| `SigningKeyResolver` (starter) | `JpaSigningKeyResolver` (anycloud override) | `@ConditionalOnMissingBean` |

### 5.1 Service / Impl 분리 (`XxxService` interface + `impl/XxxServiceImpl`)

| Domain | Interface | Impl |
|---|---|---|
| Cluster CRUD | `ClusterService` | `impl.ClusterServiceImpl` |
| Cluster connectivity / status sync | `ClusterConnectivityService` | `impl.ClusterConnectivityServiceImpl` |
| Cluster fleet health 집계 | `ClusterFleetHealthService` | `impl.ClusterFleetHealthServiceImpl` |
| Operation tracking | `OperationService` | `impl.OperationServiceImpl` |
| Cluster facade (vm + registered 통합) | `ClusterFacade` | `internal.ClusterFacadeImpl` |
| Agent bootstrap | `AgentBootstrapService` | `bootstrap.AgentBootstrapServiceImpl` |
| Fleet upgrade runs 조회 | `FleetUpgradeRunQueryService` | `upgrade.impl.FleetUpgradeRunQueryServiceImpl` |
| Agent policy audit | `AgentPolicyAuditService` | `policy.impl.AgentPolicyAuditServiceImpl` |
| VM cluster state history 조회 | `VmClusterStateHistoryQueryService` | `vmcluster.impl.VmClusterStateHistoryQueryServiceImpl` |
| Audit logging | `AuditLogger` (SPI) | `DbAuditLogger`, `InMemoryAuditLogger` |
| Audit logs 조회 | `AuditLogService` | `audit.impl.AuditLogServiceImpl` |
| Kubeconfig 발급 | `KubeconfigExportService` | `cluster.kubeconfig.internal.KubeconfigExportServiceImpl` |

### 5.2 Cross-cutting components

| Component | 위치 | 책임 |
|---|---|---|
| `@Audited` + `AuditAspect` | `domain.audit` | service method 를 SpEL 로 자동 audit |
| `BootstrapRateLimitFilter` | `common.web` | `/v1/agent-bootstrap/**` IP 별 분당 60회 |
| `IdempotencyFilter` | `common.web` | `ContentCachingResponseWrapper` 기반 응답 캐시 |
| `SensitiveDataRedactor` | `common.logging` | 로그와 응답에서 자격증명 마스킹 |
| `AgentProperties` | `domain.agent` | `@ConfigurationProperties("agent")` |

## 6. Configuration namespace

`apps/anycloud/src/main/resources/application.yaml` (+ profile / config 분할 5개 파일) 은 다음과 같습니다.

| Namespace | 용도 | Profile-specific |
|---|---|---|
| `spring.*` | Spring Boot core (datasource, JPA, Flyway, RabbitMQ) | yes |
| `springdoc.*` | OpenAPI / Swagger UI | no |
| `security.auth.*` | static token auth toggle | yes |
| `resilience4j.*` | circuit breaker / retry | yes |
| `vm-options.*` | pagination 설정 | no |
| `csp-credential.*` | encryption key | yes |
| `anycloud.*` | idempotency, helm timeout, webhook, CORS | yes |
| `webhook.*` | 외부 portal push | yes |
| `management.*` | Actuator / Prometheus | yes |
| `agent.*` | helm repo / chart / namespace (registration 가이드용) | yes |

Profile 변형은 다음과 같습니다.
- `application.yaml` — 공통입니다 (import config/*.yaml).
- `application-dev.yaml` — 로컬용입니다 (`ddl-auto=update`).
- `application-docker.yaml` — 컨테이너용입니다 (env-driven, `ddl-auto=validate`).
- `application-test.yaml` — 통합 테스트용

→ tech-debt: 파일을 넘나드는 `@Value` 주입은 검증이 없어 오타가 조용히 넘어갑니다. `cluster-agent.jwt.secret` 은 `agent.yaml` 에 있습니다. `agent.*` 는 `AgentProperties` record 로 묶여 있어 이 문제가 없습니다.

## 7. cluster-agent (Go) — package 지도

`apps/agent/internal/` 의 패키지는 다음과 같습니다.

| Package | 책임 |
|---|---|
| `bootstrap` | 첫 register RPC (JWT 검증 → identity token + cert 발급) |
| `certstore` | K8s Secret `cluster-agent-mtls` 영구화 |
| `identitystore` | K8s Secret `cluster-agent-identity` 영구화 |
| `cert_renewal` | TTL 50% 도달 시 자동 RenewCert |
| `rotation` | identity token 자동 rotation |
| `runtime` | bidi gRPC stream (heartbeat 30s + control msg dispatch) |
| `config` | allowlist ConfigMap watch |
| `allowlist` | namespace / command / helm chart 정책 enforcement |
| `controller` | command dispatcher (exec / logs / k8s ops) |
| `k8s` | discovery / dynamic / restmapper |
| `exec` | pod exec (stdin/stdout/stderr) |
| `helm` | release enumeration |
| `logstream` | gRPC pod log streaming |
| `tlsconfig` | env → TLS config |
| `leader` | leader lease (HA replicas 시) |
| `cleanup` | debug pod TTL sweeper |

### 7.1 Entry point + lifecycle

| Phase | 코드 | 행위 |
|---|---|---|
| Bootstrap (1회) | `cmd/cluster-agent/main.go` → `core.Bootstrap` | Register RPC, K8s 자동 발견 (UID/version), Secret 에 token+cert 영구화 |
| Cert renewal | `core.CertRenewal` (timer) | NotAfter 50% 도달 시 CSR + RenewCert RPC |
| Token rotation | `core.Rotation` (timer) | identity token 만료 5일 전 RotateIdentityToken RPC |
| Heartbeat | `core.RuntimeStream` | 30s 주기 AgentMessage / ControlMessage. exp backoff 재연결 |
| Shutdown | SIGTERM handler | context cancel + leader lease 반납 + debug pod cleanup |

### 7.2 Helm chart 구성

`apps/agent/deploy/helm/cluster-agent/templates/` 의 구성은 다음과 같습니다.

| Template | 책임 |
|---|---|
| `deployment.yaml` | core/installer Deployment, env, volume mounts, RBAC SA |
| `rbac.yaml` | ClusterRoles + RoleBindings (core: read-only, installer: mutating) |
| `secret.yaml` | `aipaas-agent-bootstrap` Secret (registration_token) |
| `configmap.yaml` | `aipaas-agent-allowlist` ConfigMap |
| `namespace.yaml` | `aipaas-system` |
| `_helpers.tpl` | 공통 label / selector |
| `NOTES.txt` | post-install 안내 |

**`helm.sh/resource-policy: keep`** annotation 이 Secret 들에 적용됩니다 — `helm uninstall` 시 cert/identity 를
보존합니다 (의도된 design 이며, 재기동 시 stale state 를 회피합니다). cluster 폐기 후에는 manual cleanup 이 필요합니다
([cluster-agent-secret-cleanup.md](../runbooks/cluster-agent-secret-cleanup.md)).

### 7.3 환경변수 (주요)

| Env | 기본 | 용도 |
|---|---|---|
| `BACKEND_GRPC_ADDR` | `localhost:9090` | backend endpoint |
| `REGISTRATION_TOKEN` | (Secret) | bootstrap JWT |
| `AGENT_INSTANCE_ID` | auto | HA replica 구분 |
| `AGENT_MODE` | `core` | `core` / `installer` |
| `AGENT_NAMESPACE` | `aipaas-system` | Secret/CM 위치 |
| `K8S_CLUSTER_UID` | auto | kube-system UID |
| `BACKEND_TLS_ENABLED` | `false` | TLS 활성 |
| `BACKEND_CA_CERT_PEM` | — | inline CA (혹은 `_PATH`) |
| `MTLS_DIAL_ENABLED` | `false` | client cert dial (Phase 5+) |
| `AGENT_LEADER_ELECTION` | `false` | multi-replica 리더 |

## 8. cluster-agent-spring-boot-starter — 노출 SPI

| Package | anycloud 가 사용 |
|---|---|
| `identity` | `SigningKeyResolver` (anycloud override) |
| `grpc` | gRPC server + bidi handler |
| `runtime` | `AgentSessionRegistry`, `AgentHealthService` |
| `logstream` | pod log streaming RPC |
| `terminal` | pod exec RPC |
| `autoconfigure` | `ClusterAgentAutoConfiguration` |

## 9. 의존성 다이어그램

```mermaid
graph LR
  AC[anycloud] --> ACS[cluster-agent-starter]
  AC --> COS[cluster-observability-starter]
  AC --> PUL[cluster-provisioning starter — Pulumi Java SDK]
  AG[agent Go] -.gRPC.-> AC
  AC -.K8s REST.-> KS[Target K8s clusters]
  AC --> DB[(MariaDB)]
  AC --> RMQ[(RabbitMQ)]
  AC --> PROM[(Prometheus / Thanos)]
  HELM[Helm chart] -.deploys.-> AG
  ACS -.protos.-> AG
```

- **anycloud 가 starter 에 의존합니다** — starter 의 `AgentRuntimeEndpoint` 가 backend 의 gRPC 서버입니다.
- **agent 는 starter 의 proto 만 의존합니다** — starter 의 Java 코드는 사용하지 않습니다.
- **Pulumi 호출은 외부 프로세스 exec 입니다** — Java↔Pulumi 직접 의존이 없습니다.
- **K8s 통신은 한 경로입니다.** backend 가 target cluster 의 API 를 직접 치지 않습니다. 모든 호출이
  cluster-agent 를 지나갑니다 ([`k8s-access-paths.md`](./k8s-access-paths.md)).

## 10. 테스트 커버리지

총 **689 tests PASS** 입니다 (anycloud + starter).

| 영역 | 테스트 | 평가 |
|---|---|---|
| anycloud controller | ~20 files | 신규 admin endpoint 모두 cover |
| anycloud service.cluster | 5+ files | cascade test 포함, ClusterServiceImpl 488 LOC |
| anycloud service.agent.auth | 3 신규 (revocation 11 / rotator 9 / crypto util 11) | **모두 cover** |
| anycloud service.agent.upgrade | 1 (read service) | orchestrator 일부 cover (driveOne split 후 helper 별 분리) |
| anycloud service.audit | (mock 통한 controller test) | `@Audited` aspect 자체 test 는 후속 |
| anycloud cluster.kubeconfig | `KubeconfigExportServiceTest` | cover |
| agent (Go) `core` | 6 | bootstrap / rotation cover, cert_renewal 미커버 |
| agent (Go) 외 패키지 | ~10 | `helm`, `leader`, `logstream` 없음 |
| cluster-provisioning starter | — | **거의 없음** |

→ 변경 이력은 `git log` 를 참고합니다.

## 11. 변경 빈도가 높은 hotspots

| File | 현재 LOC | 비고 |
|---|---|---|
| `ClusterServiceImpl.java` | **488** | connectivity logic 분리 적용 |
| `AdminAgentPolicyController.java` | **~630** | fleet-audit 추출 적용 |
| `AgentBootstrapServiceImpl.java` | 350+ | `@Audited` 4건 + V24 retry |
| `FleetUpgradeOrchestratorImpl.java` | 320+ | driveOne 5개 split |
| `AgentSessionRegistry.java` (starter) | 322 | Caffeine 도입 |
| `agent/internal/core/runtime.go` | — | — |

→ 책임이 너무 큰 클래스들입니다. 추가 split 을 권고합니다.

## 12. 핵심 안전장치

- **자격증명 미보관**: backend 는 cluster 의 kubeconfig 를 저장하지 않습니다. 모든 K8s 호출이 agent 를 지나갑니다.
- **identity token 영구화**: agent 가 `cluster-agent-identity` Secret 에 보관해 pod 가 재시작해도 다시 등록하지 않습니다.
- **강제 연결 해제**: `AgentSessionRegistry.evictByCluster` 가 열려 있는 stream 을 즉시 끊습니다.
- **cascade cleanup**: cluster 를 지우면 `cluster_agent` 행을 같은 트랜잭션에서 정리합니다.
- **idempotency**: `Idempotency-Key` 헤더로 24시간 동안 첫 응답을 재현합니다.
- **`@Audited` AOP**: service method 를 자동으로 audit 합니다. scheduler 나 gRPC 핸들러도 우회할 수 없습니다.
- **bootstrap rate limit**: `/v1/agent-bootstrap/**` 에 IP 별 분당 60회 상한이 걸려 있습니다.
- **민감값 마스킹**: `SensitiveDataRedactor` 가 로그와 응답에서 자격증명 형태를 가립니다.
- **pendingByRequest bounded** — Caffeine `maximumSize=10_000` + `expireAfterWrite=5m` 입니다. OOM 을 방지합니다.

## 13. 관련 문서

- [api-inventory.md](./api-inventory.md) — REST + gRPC endpoint 목록
- [feature-flows.md](./feature-flows.md) — 등록 / 갱신 / 회수 / 업그레이드 flow
- [k8s-impersonation-auth.md](./identity/k8s-impersonation-auth.md) — user RBAC pass-through 디자인입니다.
