# cluster-agent Backup sub-feature

Velero install + schedule, etcd snapshot, PKI backup. Layer 2 통합 starter
[`cluster-agent-features-spring-boot-starter`](./cluster-agent-features-starter.md) 의
sub-package `io.aipaas.cluster.agent.backup` 으로 제공. 본 문서는 Backup sub-feature 의 scope,
책임, 외부 API, SPI 를 정의합니다.

## 1. Layer 와 위치

| Starter | 책임 | 호스트 의존 SPI |
|---|---|---|
| `cluster-agent-spring-boot-starter` (Layer 1) | gRPC reverse-tunnel, agent registration, K8s API / Helm dispatcher | `AgentIdentityStore` |
| `cluster-agent-features-spring-boot-starter` — `observability` (Layer 2) | PromQL / Alertmanager / Grafana 통신, alert silence, rule | `ClusterCatalog` |
| **`cluster-agent-features-spring-boot-starter` — `backup`** (Layer 2) | **etcd / PKI 원시 백업, Velero install, Backup, Restore, Schedule** | `BackupHistoryWriter` (선택) |
| 호스트 application (e.g. anycloud) | DB, schedule, KEK, credential 통합, REST controller | — |

Layer 2 의 두 starter (observability / backup) 는 서로 독립적입니다. 어느 하나만 import 해도 동작하며, 둘 다 cluster-agent-starter 위에 build 됩니다.

본 starter 의 활성 조건은 `AgentSessionRegistry` bean 의 존재입니다. 백업 작업은 항상 명시적 cluster name 으로 호출되므로 fan-out 용 `ClusterCatalog` SPI 에는 결합하지 않습니다.

## 2. Scope — in / out

### 2.1 starter 안 (in)

| 영역 | 포함 |
|---|---|
| etcd 백업 | `EtcdBackupService` 와 `BackupResult` 입니다. snapshot bytes 를 호스트에 반환만 하며 저장은 호스트 책임입니다. |
| PKI 백업 | `PkiBackupService` 입니다. plaintext bytes 를 반환하며 암호화는 호스트 책임입니다. |
| Velero install | `VeleroInstaller` + `VeleroInstallSpec` 입니다. cluster-agent 의 `HelmReleaseService` 를 통해 velero helm chart 를 설치합니다. |
| Velero CR 관리 | `VeleroBackupService` (Backup CR), `VeleroRestoreService` (Restore CR), `VeleroScheduleService` (Schedule CR) 입니다. `APPLY_MANIFEST` 를 사용합니다. |
| 표준 정책 catalog | `BackupPolicyCatalog`, `BackupPolicyInstaller` 와 bundled `velero-policies/*.yaml` 입니다. observability-starter 의 alert-rules 패턴을 답습합니다. |
| History SPI | `BackupHistoryWriter` 입니다. 미구현 시 `NoOpBackupHistoryWriter` 가 default 로 등록됩니다. |
| AutoConfiguration | `BackupAutoConfiguration` + `BackupProperties` 입니다. host 가 의존성만 추가하면 즉시 동작합니다. |

### 2.2 starter 밖 (out — 호스트 책임)

- **K8s 버전 업그레이드** — 본 프로젝트는 K8s 버전 업그레이드 자동화를 포함하지 않습니다. 운영자가 별도 도구 (kubeadm, GKE/EKS/AKS console 등) 로 수행합니다.
- **etcd / PKI 복원** — 복원은 starter 영역 밖입니다. 운영자가 `docs/runbooks/cluster-disaster-recovery.md` 의 절차에 따라 수동으로 수행합니다. (Velero Restore CR 적용은 자체 데이터 plane 의 일부이므로 별도입니다.)
- **state persistence** — BackupHistory DB 입니다. `BackupHistoryWriter` SPI 만 제공합니다.
- **scheduling** — Spring `@Scheduled` 또는 외부 cron 입니다. Velero Schedule CR 외의 cluster-side scheduling 은 호스트 책임입니다.
- **storage destination** — S3 / GCS / NFS / Azure Blob credential 관리는 호스트의 credential 시스템 위에 있습니다.
- **KEK 관리** — PKI 백업 암호화 키입니다. Vault / KMS 통합은 host 책임
- **retention policy** — keep N days / N copies 정책
- **audit log** — host 의 audit framework 를 사용합니다.
- **REST controller** — host 가 `@RestController` 를 작성합니다. starter 는 service bean 만 제공합니다 (observability 패턴 답습).
- **UI** — frontend

### 2.3 cluster-agent / node-agent 측의 책임 분담 (starter 외부 module)

starter 는 RPC 클라이언트만 담당합니다. 서버 측 (Go binary) 의 책임은 다음과 같습니다.

| 컴포넌트 | 책임 |
|---|---|
| cluster-agent (Go) | in-cluster forwarder, `BACKUP_ETCD` / `BACKUP_PKI` 의 buffering proxy, helm install, K8s manifest apply 입니다. |
| node-agent (Go) | etcd / PKI 백업의 실제 실행입니다. RPC: `BackupEtcd`, `BackupPki`, `Health` 입니다. |
| node-agent 이름 | Velero v1.10+ 의 `velero-node-agent` 와 충돌을 회피하기 위해 본 컴포넌트는 `backup-agent` 를 사용합니다. proto package (`nodeagent.v1`) 와 service 명 (`NodeAgent`) 은 wire-compatibility 를 위해 유지합니다. |

## 3. Velero 통합

Velero 자체가 잘 만들어진 K8s-native 도구입니다. starter 의 역할은 **클릭 한 번 추상화** 입니다.

```
사용자 액션           starter 가 하는 일                              결과
─────────────────────────────────────────────────────────────────────────────────
"DR 활성화"      →   VeleroInstaller.install(cluster, spec)        helm install velero
                     → BSL / VSL CR 생성                            + 초기 BSL/VSL

"백업 일정 추가" →   VeleroScheduleService.create(                  Velero Schedule CR
                        cluster, request)                            Velero 가 자체 cron
                                                                     으로 자동 실행

"수동 백업"      →   VeleroBackupService.create(                    Backup CR 생성, Velero
                        cluster, request)                            controller 가 비동기 실행

"복구"          →    VeleroRestoreService.create(                   Restore CR 생성, Velero
                        cluster, request)                            controller 가 복구 시작

"백업 목록 조회" →   K8s LIST_RESOURCES on Backup CR                backup list JSON
"백업 상세 조회" →   K8s GET_RESOURCE  on Backup CR                 progress / errors / logs
```

Velero 가 schedule 과 retention 을 자체적으로 처리하므로 starter 와 host 는 별도 scheduler 부담이 없습니다. host DB 의 `BackupHistory` 는 표시용 cache (Velero CR 의 mirror) 또는 cross-cluster aggregation 용도로만 사용합니다.

### Storage backend 와 credential 흐름

Velero 는 S3 (또는 호환) 가 필수입니다. host 가 이미 CSP credential 을 관리하므로 다음과 같이 사용합니다.

```java
@Service
@RequiredArgsConstructor
public class VeleroSetupService {
    private final CspCredentialService credService;       // host
    private final VeleroInstaller veleroInstaller;        // starter

    public void enableDr(String cluster, String credentialId) {
        ResolvedCspCredential cred = credService.resolve(credentialId);
        veleroInstaller.install(cluster, VeleroInstallSpec.awsS3(
                cred.bucket(), cred.region(), cred.accessKey(), cred.secretKey()));
    }
}
```

starter 는 `VeleroInstallSpec` 만 받습니다 — credential 출처는 알지 못합니다. `VeleroInstallSpec` 은 AWS S3 / S3-compatible (MinIO, Wasabi, R2) / GCS / Azure Blob 의 quick-constructor 를 제공합니다.

## 4. 모듈 layout

```
libs/cluster-agent-features-spring-boot-starter/
├── build.gradle
├── src/main/
│   ├── java/io/aipaas/cluster/agent/backup/
│   │   ├── autoconfigure/
│   │   │   ├── BackupAutoConfiguration.java
│   │   │   └── BackupProperties.java
│   │   ├── core/
│   │   │   └── BackupException.java
│   │   ├── node/                       (etcd / PKI raw backup)
│   │   │   ├── EtcdBackupService.java
│   │   │   ├── PkiBackupService.java
│   │   │   ├── BackupResult.java
│   │   │   ├── BackupHistoryWriter.java
│   │   │   ├── NoOpBackupHistoryWriter.java
│   │   │   └── impl/
│   │   │       ├── BackupDispatchSupport.java
│   │   │       ├── EtcdBackupServiceImpl.java
│   │   │       └── PkiBackupServiceImpl.java
│   │   └── velero/
│   │       ├── VeleroInstaller.java
│   │       ├── VeleroInstallSpec.java
│   │       ├── VeleroInstallResult.java
│   │       ├── VeleroBackupService.java       VeleroBackupRequest.java
│   │       ├── VeleroRestoreService.java      VeleroRestoreRequest.java
│   │       ├── VeleroScheduleService.java     VeleroScheduleRequest.java
│   │       ├── VeleroCrResult.java
│   │       ├── BackupPolicy.java              BackupPolicyApplyResult.java
│   │       ├── BackupPolicyCatalog.java       BackupPolicyInstaller.java
│   │       └── impl/
│   │           ├── BackupPolicyInstallerImpl.java
│   │           ├── VeleroBackupServiceImpl.java
│   │           ├── VeleroCrApplier.java
│   │           ├── VeleroInstallerImpl.java
│   │           ├── VeleroRestoreServiceImpl.java
│   │           └── VeleroScheduleServiceImpl.java
│   └── resources/
│       ├── META-INF/spring/
│       │   └── org.springframework.boot.autoconfigure.AutoConfiguration.imports
│       └── velero-policies/
│           ├── daily-full-cluster.yaml
│           ├── hourly-workloads.yaml
│           └── weekly-pv-snapshots.yaml
└── src/test/...
```

cluster-agent 와의 RPC 통신은 별도 proxy client 를 두지 않고, 각 service 의 `impl` 이 cluster-agent-starter 의 `AgentSessionRegistry` (또는 `HelmReleaseService`) 를 직접 사용합니다.

## 5. AutoConfiguration 활성 조건

```java
@AutoConfiguration
@ConditionalOnClass(EtcdBackupService.class)
@ConditionalOnBean(AgentSessionRegistry.class)
@EnableConfigurationProperties(BackupProperties.class)
public class BackupAutoConfiguration { ... }
```

- `AgentSessionRegistry` bean 이 host 에 존재해야 활성화됩니다 — cluster-agent-starter 가 자동으로 제공합니다.
- 모든 bean 은 `@ConditionalOnMissingBean` 이므로 host 가 override 가능합니다.
- per-feature kill-switch 가 있습니다.
  - `cluster-backup.node.enabled=false` — etcd / PKI raw backup service 비활성.
  - `cluster-backup.velero.enabled=false` — Velero 관련 bean 일괄 비활성.
- `BackupHistoryWriter` 가 host 에서 등록되지 않으면 `NoOpBackupHistoryWriter` 가 default 입니다. production 환경에서는 host 가 DB-backed 구현 등록을 권장합니다.

## 6. Configuration properties

```yaml
cluster-backup:
  node:
    chunk-size: 4194304
    encryption-enabled: false

  velero:
    auto-install: false
    chart-version: "8.2.0"
    namespace: "velero"
    default-ttl: "720h"
    auto-install-policies: true
```

각 필드 의미는 `BackupProperties` 의 javadoc 을 참조합니다.

## 7. node-agent 이름과 wire-compatibility

Velero v1.10+ 가 자체 DaemonSet `velero-node-agent` (구 restic) 를 띄웁니다. 충돌 회피를 위해 본 컴포넌트는 `backup-agent` 이름을 사용합니다.

- 위치: `apps/agent/cmd/backup-agent/`, `apps/agent/deploy/helm/backup-agent/`.
- DaemonSet / Service / ServiceAccount 이름 + label: `app: backup-agent`.
- cluster-agent 의 label selector 상수: `app=backup-agent`.

wire-compatibility 를 위해 다음은 유지합니다.

- proto package: `nodeagent.v1`.
- proto service: `NodeAgent`.
- Go internal package: `apps/agent/internal/nodeagent/{server,backup}`.

## 8. Impersonation SPI 호환성

cluster-backup-starter 가 K8s API 호출을 수반하는 경우 (Velero install / backup / restore 의 cluster-side plan, apply 등) — cluster-agent-spring-boot-starter 의 `ImpersonationContext` SPI 를 통과해야 user RBAC 가 적용됩니다.

원칙은 다음과 같습니다.

- starter 의 K8s 호출 entry 는 `KubeResourceService` / `HelmReleaseService` 같은 cluster-agent starter 의 service 를 거치는 패턴을 유지합니다 → impersonation 이 자동 적용됩니다.
- starter 가 자체 K8s client (예: file-system snapshot 용 raw client) 를 보유한다면, host 의 `ImpersonationContext` bean 을 `ObjectProvider` 로 받아 `current()` 결과를 RPC payload 에 직접 매핑합니다.
- 단독 admin action (예: backup catalog seed, scheduled cleanup) 은 holder 가 empty 상태이므로 자연스럽게 admin-equivalent 로 동작합니다. 명시적 user impersonation 이 필요한 호출은 caller 가 `ThreadLocalImpersonationContext.withIdentity` 로 wrap 합니다.

상세 design 과 운영 활성화는 [`../identity/k8s-impersonation-auth.md`](../identity/k8s-impersonation-auth.md) 를 참조합니다.

---

Future enhancement 후보: etcd streaming proxy, backup encryption SPI. trigger 발생 시 별 sprint 로 진행.
