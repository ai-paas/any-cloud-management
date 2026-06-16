# cluster-provisioning-spring-boot-starter

Multi-cloud VM Kubernetes 클러스터 프로비저닝을 Pulumi CLI 로 위임하는 Spring Boot 스타터입니다.

**Status**: `0.1.0` (정식 release) — Pulumi CLI 오케스트레이션 + `PulumiProvisioningService`(provision/preview/destroy) + state backup scheduler/validator **구현이 모두 starter 로 이동 완료**. 외부 backend 는 `ProcessExecutor` / `PulumiExecutionConfig` / `ClusterDescriptorRepository` / `PulumiBackupPropertiesProvider` SPI 포트만 채우면 전체 provisioning 스택을 재사용합니다.

## Overview

anycloud 의 `service/provisioning/*` 를 host-agnostic SPI 로 추출한 모듈입니다. 외부 backend (예: AI/ML 클러스터 SaaS) 가 동일한 Pulumi 인프라 코드를 재사용할 수 있도록 설계되어 있습니다.

### 책임 분리 (mechanism / policy)

starter 는 Pulumi 오케스트레이션 **정책**(어떤 명령을, 어떻게 파싱)을 소유하고, OS 프로세스 생성 **메커니즘**과 설정 **소스**는 SPI 포트로 host 에 위임합니다.

| Starter (재사용 가능) | Host (anycloud 등) |
| --- | --- |
| Pulumi CLI 오케스트레이션 (`PulumiCommandService` **구현** — 명령 구성·민감 인자 마스킹·`--json` 파싱) | 프로세스 생성 메커니즘 (`ProcessExecutor` — graceful shutdown 등) |
| `--json` event stream 파싱 (`ProvisionEvent` + `ProvisionEventBus`) | Pulumi 실행 config (`PulumiExecutionConfig` — binary/projectDir/passphrase/backend) |
| 표준 output schema (`ProvisioningOutput`) + jakarta validation | `ClusterEntity` 영속화 · REST controller / SSE endpoint |
| Multi-cloud provider 추상화 | CSP credential storage 정책 · 자격증명 → stack config 매핑 · stale-lock 복구 정책 |

## Quick Start

### 1. Dependency

```groovy
// settings.gradle 에 module 등록 후
implementation project(':cluster-provisioning-spring-boot-starter')

// 또는 publishToMavenLocal 후
implementation 'io.aipaas.cluster:cluster-provisioning-spring-boot-starter:0.1.0-SNAPSHOT'
```

### 2. application.yml toggle

```yaml
cluster-provisioning:
  enabled: true   # default. false 로 설정하면 autoConfig 가 비활성화됩니다 (kill-switch).
  pulumi:
    binary-path:        # null 이면 PATH 의 pulumi 를 사용합니다. 절대 경로 명시 가능합니다.
    project-dir:        # null 이면 caller working directory 를 사용합니다.
    state-backend-url:  # s3://... — RustFS / S3 login 시 사용합니다.
    command-timeout: 30m
  state-backup:
    # backup scheduler/validator 의 cron schedule 만 여기서 읽습니다 (@Scheduled).
    cron: "0 0 3 * * *"               # 매일 03:00. 미설정 시 기본값.
    restore-dry-run:
      cron: "0 30 3 * * *"            # 매일 03:30.
```

> backup 의 **enable / directory / retention / deep-validation** 은 위 property 가 아니라 host 가
> 구현하는 `PulumiBackupPropertiesProvider` SPI 로 공급합니다 (anycloud 는 `pulumi.backup.*` config 를
> 어댑터로 노출). starter 는 cron schedule 만 직접 읽습니다.

### 3. Host 가 구현할 SPI

| SPI | 역할 | Default impl |
| --- | --- | --- |
| `ProcessExecutor` | OS 프로세스 생성 (execute / executeStreaming) | `DefaultProcessExecutor` (ProcessBuilder, shutdown 추적 없음). host 가 graceful-shutdown 실행기 주입 권장 |
| `PulumiExecutionConfig` | Pulumi 실행 config (binary / projectDir / env / passphrase / backend) | `ProvisioningProperties` 의 `pulumi.*` 매핑. host config 가 직접 implements 가능 |
| `ClusterProvisioningSink` | 결과 및 event 를 host backend 의 DB / message bus 로 push 합니다 | NoOp (log 만 출력) |
| `PulumiCredentialProvider` | CSP 자격증명 lookup 을 수행합니다 | 없음 (host 필수 구현) |
| `ClusterDescriptor` | host Entity 의 read-only 추상화입니다 | 없음 (잔여 service 이동 시 필요) |
| `ClusterDescriptorRepository` | `findAllActive` / `findById` / `findByClusterName` port 입니다 | 없음 |
| `PulumiBackupPropertiesProvider` | host 의 backup 설정 추상화입니다 | `DefaultPulumiBackupPropertiesProvider` (backup 비활성) |

> `PulumiExecutionConfig` 의 메서드 시그니처는 Lombok `@Getter` 가 생성하는 형태(`getBinaryPath` / `resolveProjectDir` / `getEnvironment` / `getPassphrase` / `getBackendUrl` / `isAutoCreateStack` / `getSecretsProvider`)와 일치합니다. 그래서 anycloud 의 `PulumiProperties`(prefix `pulumi`)는 추가 메서드 없이 `implements PulumiExecutionConfig` 만으로 config 소스가 되며, 기존 `pulumi.*` application.yml 키를 그대로 유지합니다 (config migration 0).

예시 host adapter:

```java
@Component
public class VmClusterDescriptorAdapter implements ClusterDescriptorRepository {
    private final VmClusterRepository repo;
    public VmClusterDescriptorAdapter(VmClusterRepository repo) { this.repo = repo; }

    @Override
    public List<ClusterDescriptor> findAllActive() {
        return repo.findAllByStatus(VmClusterStatus.ACTIVE).stream()
                .map(e -> new ClusterDescriptor() {
                    public String getClusterName() { return e.getClusterName(); }
                    public String getProvider() { return e.getProvider(); }
                    // ... 등
                })
                .toList();
    }
}
```

## Module Layout

```
io.aipaas.cluster.provisioning/
├── core/                              ← 도메인 type + SPI
│   ├── ProvisioningRequest            (record)
│   ├── ProvisionEvent                 (record — internal event bus 형)
│   ├── ProvisioningOutput             (record + jakarta validation)
│   ├── ProvisioningOutputValidationException
│   ├── PulumiCommandResult            (POJO)
│   ├── ProcessExecutor                (SPI — OS 프로세스 생성 포트)
│   ├── PulumiExecutionConfig          (SPI — Pulumi 실행 config 포트)
│   ├── ClusterProvisioningSink        (SPI)
│   ├── PulumiCredentialProvider       (SPI)
│   ├── ClusterDescriptor              (SPI)
│   ├── ClusterDescriptorRepository    (SPI)
│   └── PulumiBackupPropertiesProvider (SPI)
├── service/                           ← 평범한 POJO (autoConfig 가 @Bean 으로 등록)
│   ├── ProvisionEventBus              (Reactor multicast)
│   ├── ProvisioningOutputMapper       (raw → typed)
│   ├── PulumiCommandService           (interface)
│   ├── PulumiCommandServiceImpl       (기본 구현 — ProcessExecutor + PulumiExecutionConfig 위임)
│   └── DefaultProcessExecutor         (ProcessExecutor 기본 구현 — ProcessBuilder)
└── autoconfigure/
    ├── ProvisioningAutoConfiguration  (@AutoConfiguration + @Bean + @ConditionalOnMissingBean)
    └── ProvisioningProperties         (@ConfigurationProperties)
```

잔여 service 이동 항목 (`PulumiProvisioningService`, impl, validator, scheduler) 의 trigger 조건과 일정은 `docs/ROADMAP.md` §3 을 참조하시기 바랍니다.

## Bean Registration Pattern

다른 스타터 (cluster-agent / observability / lifecycle) 와 동일한 패턴을 사용합니다. `@AutoConfiguration` + `@Bean` + `@ConditionalOnMissingBean` 조합으로 평범한 POJO 를 명시 등록합니다.

- Anti-pattern: `@Component` + host 측 `@ComponentScan` 의존
- Correct: autoConfig 가 명시 등록하며, host override 시 `@ConditionalOnMissingBean` 으로 자동 비활성화됩니다

## Tests

`ProvisionEventBusTest` 가 현재 등록된 테스트입니다. 다음 5 가지 케이스를 검증합니다.

- publish 후 단일 subscriber 가 수신
- multicast (다수 subscriber 동시 수신)
- null event 는 silent skip
- buffer 의 late-subscriber replay (Reactor `onBackpressureBuffer` 특성)
- operation-id filter 는 subscriber 책임

잔여 service 이동 완료 시 `ProvisioningOutputMapper` / `PulumiStateBackupValidator` 등의 테스트 케이스가 추가됩니다.

## Related Docs

- `docs/architecture/starters/cluster-provisioning-starter.md` — 스타터 설계 문서입니다.
- `docs/architecture/starters/starter-publishing.md` — 4 개 스타터 공통 publish 가이드입니다.
- `docs/ROADMAP.md` §3 — 잔여 이동 작업 및 trigger 조건입니다.

## License

Apache License 2.0.


---

## 프로젝트 컨텍스트 · 호환성 (any-cloud-management)

본 starter 는 [any-cloud-management](../../README.md) 모노레포의 일부로 함께 빌드 / publish 됩니다.
외부 consumer (자체 backend) 는 동일한 Maven coordinate 로 임포트할 수 있습니다.

| 항목 | 호환 버전 |
|---|---|
| Java | **21** (Records / sealed / virtual threads 사용) |
| Spring Boot | **3.2.x** (BOM) |
| Gradle | **8.10.x** (wrapper 고정) |
| Jackson | 2.17.2 (root `ext.jacksonVersion` 단일 진실) |
| Lombok | 1.18.36 (annotationProcessor + compileOnly 동일) |
| Resilience4j | 2.2.0 |

starter 변경 시 `ratchetFrom origin/main` Spotless 검사 (`make format`) 와 `make backend-test`
통과 후 commit. 컨벤션 + 도메인 가이드는 [`docs/`](../../docs/) 참조.