# Persistence Layer Convention

신규 repository 를 작성할 때 따라야 하는 위치 / 명명 / 가시성 규칙. repo 16 개가 모두 hexagonal
port 패턴 (`domain/{X}/port/`) 으로 통일되어 있다 (feature-first migration, 2026-06-12 완료).

## Decision

Spring Data JPA repository 는 `domain/{X}/port/{Domain}Repository.java` 위치에 **interface 로만**
정의한다. JPA 가 런타임에 proxy 구현체를 만들어 준다.

대안이었던 Spring convention (`repository/` 단일 root) 은 **채택하지 않음** — 도메인이 커질수록
한 폴더에 16+ interface 가 쌓여 readability 가 떨어지기 때문. 도메인 폴더 안에 repository 가
함께 있어야 "addon 의 persistence 진입점" 같은 질문에 5초 내 답할 수 있다.

## Layout

```
domain/{X}/
├── {Domain}Service.java               # public interface (도메인 facade)
├── {Domain}Entity.java                # JPA @Entity (feature-first migration 로 모두 domain 안)
├── {Domain}.java                      # domain record (optional)
├── port/
│   ├── {Domain}Repository.java        # Spring Data JPA interface
│   └── ...                            # 동일 도메인의 다른 entity repository
├── internal/
│   └── {Domain}ServiceImpl.java       # @Service constructor inject repository
└── ...
```

## Current inventory (16 repositories)

| 도메인 | repository | entity |
|---|---|---|
| addon | `domain/addon/port/ClusterAddonRepository` | ClusterAddonEntity |
| agent | `domain/agent/port/AgentSigningKeyRepository` | AgentSigningKeyEntity |
| agent | `domain/agent/port/BootstrapJtiRepository` | BootstrapJtiEntity |
| agent | `domain/agent/port/ClusterAgentRepository` | ClusterAgentEntity |
| agent | `domain/agent/port/FleetUpgradeRunRepository` | FleetUpgradeRunEntity |
| agent | `domain/agent/port/IdempotencyRecordRepository` | IdempotencyRecordEntity |
| audit | `domain/audit/port/AuditLogRepository` | AuditLogEntity |
| backup | `domain/backup/port/BackupHistoryRepository` | BackupHistoryEntity |
| cluster | `domain/cluster/port/ClusterRepository` | ClusterEntity |
| credential | `domain/credential/port/CspCredentialRepository` | CspCredentialEntity |
| helmrepo | `domain/helmrepo/port/HelmRepoRepository` | HelmRepoEntity |
| oidcbinding | `domain/oidcbinding/port/OidcGroupBindingRepository` | OidcGroupBindingEntity |
| operation | `domain/operation/port/OperationRepository` | OperationEntity |
| vmcluster | `domain/provisioning/port/VmClusterRepository` | VmClusterEntity |
| vmcluster | `domain/provisioning/port/VmClusterStateHistoryRepository` | VmClusterStateHistoryEntity |
| vmcluster | `domain/provisioning/port/WorkflowMessageLogRepository` | WorkflowMessageLogEntity |

## Rules

1. **Visibility**: repository interface 는 public — Spring proxy 생성에 필요. `@Repository`
   는 선택 (JpaRepository 상속 시 Spring 이 자동 인식).
2. **Direct injection**: controller 는 repository 를 **직접 inject 하지 않는다**. service
   를 거친다 — controller 의 책임 분리.
3. **Custom query**: 단순 derived query (`findByStatus`, `findByClusterNameAndAgentInstanceId`)
   는 method name 만으로 충분. 복잡한 join 은 `@Query` JPQL 사용 — `@Query(nativeQuery=true)`
   는 DB-specific syntax 가 명백한 경우만.
4. **Pagination**: 1000+ row 가능성이 있는 finder 는 `Page<T> findXxx(Pageable)` 변형을
   추가 제공. caller 가 `findAll()` 전체 로드를 강요받지 않도록.
5. **DB-side filter**: in-memory `findAll().stream().filter(...)` 패턴은 신규 코드에서 금지
   — 처음부터 `findByStatus(ACTIVE)` 같은 derived query 로 표현한다.

## Anti-patterns

```java
// ❌ Controller 가 repository 직접 inject
@RestController
class FleetUpgradeController {
    private final FleetUpgradeRunRepository runRepository;   // service 를 거치지 않음
}

// ❌ Service 안에서 다른 도메인의 repository 직접 사용
class AddonService {
    private final ClusterRepository clusterRepository;    // ClusterService 를 사용해야 함
}

// ❌ Repository 가 도메인 root 에 있음 (port/ 가 아닌 위치)
domain/cluster/ClusterRepository.java                    // port/ 로 이동 필요
```

## Migration trigger

신규 repository 추가 시 본 규칙을 즉시 적용. 기존 16 개는 모두 규칙을 따르므로 추가 마이그레이션
불요. 위 anti-pattern 발견 시 별도 PR 로 정리.

## DB 가 갖지 않는 책임

다음은 anycloud backend DB 에 두지 **않는** 데이터. starter (Layer 2) 의 SPI 가 stateless 로
처리하거나, 외부 시스템 (Keycloak / K8s) 이 truth 를 보관:

| 데이터 | Truth 위치 | 이유 |
|---|---|---|
| OIDC 사용자 / 그룹 / role membership | Keycloak | starter 가 IdP-native — backend 가 mirror 안 함 |
| group → ClusterRole 매핑 (RBAC 정책 결과) | K8s ClusterRoleBinding | K8s 가 truth, drift 회피, GitOps 호환 |
| Addon 별 ClusterRole 정의 | addon helm chart 가 동봉 | chart 가 자체 RBAC 가짐 |
| 운영자 catalog (binding 추천) | starter classpath resource (`binding-templates.yaml`) | starter 외부 재배포 호환 |
| Binding audit log | SLF4J / 외부 SIEM (`BindingAuditSink` SPI) | host 선택, default 는 NoOp |
| Observability (Prometheus metrics, Alertmanager rules) | cluster 안 Prometheus | observability starter 는 query passthrough 만 |

→ backend DB 의 책임은 cluster registry / Pulumi metadata / CSP credential / workflow audit /
LRO state 로 명확히 좁아짐. 자세한 분리: [`starters.md`](./starters.md).
