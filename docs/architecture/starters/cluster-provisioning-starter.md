# cluster-provisioning-spring-boot-starter

Pulumi 기반 멀티 클라우드 Kubernetes 클러스터 프로비저닝을 host backend 가 재사용할 수 있도록 추출한
Spring Boot 스타터. SPI + autoConfig + 오케스트레이션 service 가 starter 안에 위치하고, host (anycloud)
는 4 SPI (`ClusterProvisioningSink`, `PulumiCredentialProvider`, `ClusterDescriptor*`,
`PulumiBackupPropertiesProvider`) 만 구현하면 동작.

## 1. Scope

### 1.1 starter 안 (in)

- **Pulumi CLI 실행** — `pulumi up` / `preview` / `destroy` / `refresh` / `stack ls` 의 프로세스 spawn 을 추상화합니다. `cluster-provisioning.pulumi.binaryPath`, `projectDir`, `stateBackendUrl`, `commandTimeout` 설정을 노출합니다.
- **`--json` event stream 파싱** — Pulumi 의 `--json` flag output 을 line-by-line 으로 파싱하여 `ProvisionEvent` / `ProvisioningEvent` 로 정규화합니다.
- **Real-time event 전달** — observer pattern (`ProvisionEventBus`, Reactor multicast Sinks) 으로 caller (host backend) 가 SSE / WebSocket / message bus 등 자체 채널로 forward 할 수 있습니다.
- **표준 output 계약** — 모든 provider 가 export 해야 하는 `ProvisioningOutput` 레코드와 jakarta validation 을 통한 schema 검증을 제공합니다. `ProvisioningOutputMapper` 가 raw `Map<String, Object>` 를 강타입으로 매핑합니다.
- **Provider 추상화** — provider 별 stack config 표준화입니다. 본 스타터는 Pulumi project (Go) 의 외부 wrapper 입니다. Pulumi project 자체는 별 repo / `infra/pulumi` 에 위치합니다.
- **State backup properties 추상화** — `PulumiBackupPropertiesProvider` SPI 와 default impl (비활성) 을 제공합니다. 실제 scheduler 의 이동은 잔여 작업입니다.

### 1.2 starter 밖 (out — host 책임)

- **`ClusterEntity` / `OperationEntity` 영속화** — host 가 DB schema 결정 및 갱신을 담당합니다.
- **CSP credential storage / 평문 정책** — host 결정사항입니다 (anycloud 는 평문 저장).
- **Pulumi binary install** — host 의 Dockerfile / VM provision 영역입니다.
- **REST controller / SSE endpoint** — 스타터는 service bean 만 제공합니다.
- **Pulumi project (Go) 자체** — 별 repo / submodule 입니다. 스타터는 CLI wrapper 만 담당합니다.

### 1.3 host 구현 SPI

`libs/cluster-provisioning-spring-boot-starter/src/main/java/io/aipaas/cluster/provisioning/core/` 의
5 interface — host 가 구현하면 starter 의 service bean 들이 자동 wire.

| SPI | 역할 | Default impl |
| --- | --- | --- |
| `ProcessExecutor` | OS 프로세스 spawn 추상화 (Pulumi CLI 실행). host 가 graceful-shutdown / in-flight 추적 통합 가능. | `DefaultProcessExecutor` (ProcessBuilder 기반) |
| `PulumiExecutionConfig` | Pulumi CLI 실행 config (binaryPath / projectDir / stateBackendUrl / commandTimeout). host 의 `application.yml` 의 `pulumi.*` 와 결합. | `DefaultPulumiExecutionConfig` |
| `ClusterDescriptor` | host Entity (예: `VmClusterEntity`) 의 read-only 추상화 (clusterName / provider / stackName 등). | 없음 (host 가 필수 구현) |
| `ClusterDescriptorRepository` | `findAllActive` / `findById` / `findByClusterName` port — host DB 조회. | 없음 (host 가 필수 구현) |
| `PulumiBackupPropertiesProvider` | host 의 application.yml prefix 와 결합되는 backup 설정 (isEnabled / isRestoreDryRunEnabled 등). | `DefaultPulumiBackupPropertiesProvider` (backup 비활성) |

**전체 5 SPI 구현 예시** — anycloud backend 가 실 구현 reference (`apps/anycloud/.../domain/provisioning/internal/`):
- `CommandExecutionProcessExecutor` — `ProcessExecutor` impl (graceful shutdown 통합)
- `PulumiProperties` — `PulumiExecutionConfig` 직접 implement (Lombok `@Getter` + method 0 추가)
- `VmClusterDescriptorRepositoryAdapter` — `ClusterDescriptorRepository` impl (JPA)
- `AnycloudPulumiBackupPropertiesProvider` — `PulumiBackupPropertiesProvider` impl (yml binding)

**Event publish**: starter 가 `ProvisionEventBus` (Reactor multicast) 를 자동 노출 — host 는 별도 sink
구현 없이 `@Autowired ProvisionEventBus` 로 subscribe (SSE / WebSocket / RabbitMQ forward). 즉
"sink" 는 별도 SPI 가 아닌 reactive subscribe 패턴.

**Credential resolution**: host 의 `provisioningRequest.environment()` 에 CSP env 를 미리 채워서 호출.
starter 는 별도 credential SPI 미보유 — host 의 credential service 가 환경변수 형태로 inject.

production-ready 검증: [`examples/spring-boot-host/`](../../../examples/spring-boot-host/) 의 minimal
consumer 가 5 SPI 만 구현하고 starter 가 정상 wire 되는지 reference example.

## 2. 모듈 layout

```
libs/cluster-provisioning-spring-boot-starter/
  build.gradle                              — Spring Boot starter (api spring-boot-starter / validation, Reactor, Micrometer, Jackson)
  src/main/java/io/aipaas/cluster/provisioning/
    core/
      ProvisioningRequest.java              — record (clusterName, provider, config, credentials)
      ProvisioningEvent.java                — record (stackName, type, detail, timestamp) — host SPI 형
      ProvisionEvent.java                   — record + fromPulumiJson 팩토리 — internal event bus 형
      ProvisioningOutput.java               — record + jakarta validation (8 CSP 공통 표준 키)
      ProvisioningOutputValidationException.java
      PulumiCommandResult.java              — Lombok @Builder POJO (exitCode/stdout/stderr)
      ClusterProvisioningSink.java          — SPI 1 (lifecycle hook)
      PulumiCredentialProvider.java         — SPI 2 (credential lookup)
      ClusterDescriptor.java                — SPI 3 (host Entity 추상화)
      ClusterDescriptorRepository.java      — SPI 4 (read port)
      PulumiBackupPropertiesProvider.java   — SPI 5 (+ DefaultPulumiBackupPropertiesProvider)
    service/
      ProvisionEventBus.java                — Reactor Sinks.Many multicast + onBackpressureBuffer(1024)
      ProvisioningOutputMapper.java         — raw Map → ProvisioningOutput (+ validation)
      PulumiCommandService.java             — interface (selectOrCreateStack / up / destroy / stackOutputs / streaming variants)
    autoconfigure/
      ProvisioningAutoConfiguration.java    — @AutoConfiguration + @Bean × 4 (+ kill-switch via @ConditionalOnProperty)
      ProvisioningProperties.java           — @ConfigurationProperties("cluster-provisioning") (pulumi / stateBackup)
  src/main/resources/META-INF/spring/
    org.springframework.boot.autoconfigure.AutoConfiguration.imports
  src/test/java/io/aipaas/cluster/provisioning/service/
    ProvisionEventBusTest.java              — multicast / buffer / null skip 검증
```

`ProvisioningAutoConfiguration` 이 등록하는 빈은 다음과 같습니다.

- `ProvisionEventBus` (`@ConditionalOnMissingBean`)
- `ProvisioningOutputMapper` (`@ConditionalOnMissingBean`, `jakarta.validation.Validator` 주입)
- `ClusterProvisioningSink` NoOp 디폴트 (`@ConditionalOnMissingBean`)
- `PulumiBackupPropertiesProvider` 기본 비활성 impl (`@ConditionalOnMissingBean`)

`ClusterDescriptorRepository` 는 default impl 이 없으며, host 가 반드시 자체 빈을 제공해야 합니다.

## 3. Properties 계약

`@ConfigurationProperties(prefix = "cluster-provisioning")` 의 실제 구조입니다.

```yaml
cluster-provisioning:
  enabled: true                          # kill-switch. false 시 autoConfig 전체 비활성.
  pulumi:
    binary-path:                         # null → PATH 의 pulumi. 명시 시 절대 경로.
    project-dir:                         # null → caller working directory.
    state-backend-url:                   # s3://... — RustFS / S3 login 시 사용.
    command-timeout: 30m                 # default Duration.ofMinutes(30).
  state-backup:
    enabled: false                       # 활성 시 PulumiStateBackupScheduler sweep.
    interval: 6h                         # default Duration.ofHours(6).
    backup-bucket:                       # null → pulumi-state-backups.
```

레코드 ctor 의 normalization (null → default) 은 `ProvisioningProperties` 컴팩트 생성자에서 처리합니다.

## 4. 관련 파일

- `libs/cluster-provisioning-spring-boot-starter/build.gradle`
- `libs/cluster-provisioning-spring-boot-starter/src/main/java/io/aipaas/cluster/provisioning/core/*.java`
- `libs/cluster-provisioning-spring-boot-starter/src/main/java/io/aipaas/cluster/provisioning/service/*.java`
- `libs/cluster-provisioning-spring-boot-starter/src/main/java/io/aipaas/cluster/provisioning/autoconfigure/*.java`
- `libs/cluster-provisioning-spring-boot-starter/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- `settings.gradle` — module include
- 이동 완료 source: 기존 `service/provisioning/*` → starter 의 `io.aipaas.cluster.provisioning.*` (III-54/55)
- 외부 reference: `infra/pulumi/` (Go binary, 별 ownership)

---

후속 검토 항목: Pulumi Automation API (Java SDK) 활용 vs CLI subprocess, multi-tenant 격리 (process-level), Maven Central 정식 release 검토. trigger 발생 시 별 sprint 로 진행.
