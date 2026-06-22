# cluster-provisioning-spring-boot-starter

Multi-cloud VM Kubernetes 클러스터 프로비저닝을 host backend 가 재사용할 수 있도록 추출한 Spring Boot
스타터. **Pulumi Automation Java SDK** 기반 in-JVM 오케스트레이션 — Pulumi binary 는 필요하지만
**Go runtime 의존 0**, ProcessBuilder fork 없이 Java JVM 안에서 stack lifecycle 호출.

> 운영 사용 가이드 + 새 CSP 추가 절차는 [`libs/cluster-provisioning-spring-boot-starter/README.md`](../../../libs/cluster-provisioning-spring-boot-starter/README.md) 참조. 본 문서는 *architectural decision* 만 기록.

## 1. Scope

### 1.1 starter 안

- **Pulumi Automation Java SDK lifecycle** — `LocalWorkspace` + `WorkspaceStack` 으로 in-JVM
  `up`/`preview`/`destroy`/`refresh` 호출 (`AutomationProvisioningService`).
- **EngineEvent stream 처리** — `EngineEventAdapter` 가 Pulumi `EngineEvent` 를 `ProvisionEvent` 로
  정규화 후 Reactor `ProvisionEventBus` (multicast Sinks) 로 publish.
- **7-CSP provider 추상화** — `AbstractKubeadmProvisioner` template 위 `Aws/Gcp/Azure/Oci/Alibaba/DigitalOcean/Openstack`
  실 구현. 각 provider 는 `provisionResources(ctx, spec) → ProvisionedCluster` 만 책임, 표준 outputs
  schema 조립은 base class.
- **CSP credential isolation** — `CspCredentialPulumiConfigMapper` 가 env (AWS_ACCESS_KEY_ID) →
  Pulumi stack config secret (aws:accessKey) 변환. state backend (RustFS) env 와 충돌 방지.
- **표준 output 계약** — `ProvisioningResult` record + jakarta validation. `ProvisioningResultMapper`
  가 raw `Map<String, Object>` → 강타입 변환.

### 1.2 starter 밖 (host 책임)

- `VmClusterEntity` / `OperationEntity` 영속화 — host DB schema.
- CSP credential storage / 평문 정책 — anycloud 결정사항 (평문 저장).
- Pulumi binary install — host Dockerfile (anycloud 의 `Dockerfile.pulumi` 는 plugin pre-install 포함).
- REST controller / SSE endpoint — starter 는 service bean 만.

### 1.3 host 구현 SPI

| SPI | 역할 | Default impl |
| --- | --- | --- |
| `ExecutionConfig` | Pulumi 실행 config (state backend URL / passphrase / secrets-provider / stack prefix) | autoconfig 가 `ProvisioningProperties` 기반 default bean 제공 — host (anycloud `PulumiProperties`) 는 `implements` 만으로 override |

이전 5 SPI (`ClusterProvisioningSink`, `PulumiCredentialProvider`, `ClusterDescriptor*`,
`PulumiBackupPropertiesProvider`, `ProcessExecutor`) 는 **Pulumi Go→Java 마이그레이션 후 삭제**.
이유: in-JVM 으로 인해 외부 프로세스 추적/credential injection/state backup 이 모두 starter 내부에서
처리되거나 (event bus) host 가 직접 `ProvisioningRequest.credentialEnvironment` 로 inject (credential
flow) 하면 충분.

**Event subscribe**: host 는 `@Autowired ProvisionEventBus eventBus` → `eventBus.asFlux().subscribe(...)`.

**Credential inject**: host 가 `ProvisioningRequest.builder().credentialEnvironment(Map.of(...))` 에
CSP env 채워 호출. starter 의 `CspCredentialPulumiConfigMapper` 가 자동 변환.

## 2. 모듈 layout

`libs/cluster-provisioning-spring-boot-starter/src/main/java/io/aipaas/cluster/provisioning/`:

```
api/                    ← Public surface (host 가 직접 import)
├── ProvisioningService               interface — provision/preview/refresh/destroy/outputs
├── ProvisioningRequest               record — provider + cluster + credential + config
├── ProvisioningResult                record + jakarta validation
├── ProvisioningPreview               record
├── ProvisionEvent                    record
├── ExecutionConfig                   SPI port
└── exception/                        ProvisioningExecutionException, ProvisioningResultValidationException

internal/               ← starter 내부 구현 (host 가 직접 import X)
├── AutomationProvisioningService     ProvisioningService impl — Pulumi LocalWorkspace lifecycle
├── EngineEventAdapter                EngineEvent → ProvisionEvent 변환
├── ProvisionEventBus                 Reactor multicast bus
├── ProvisioningResultMapper          raw → ProvisioningResult + validation
└── CspCredentialPulumiConfigMapper   env → stack config Map registry

program/                ← in-JVM Pulumi 프로그램
├── ProvisionerOrchestrator           Pulumi inline program — ProviderRegistry dispatch
├── ClusterSpec                       record + Builder + normalize()
├── Defaults                          ProviderDefaults table + cross-cutting (masterCount odd / rootDisk≥50)
├── DatabaseSpec, JoinTokens, ResourceNames, ProviderName, K8sConstants, KubeadmUserData
└── provisioner/
    ├── ProviderProvisioner           CSP contract
    ├── ProviderRegistry              canonical name → provisioner
    ├── AbstractKubeadmProvisioner    공통 lifecycle + 표준 outputs assembly + node array
    ├── {Aws,Gcp,Azure,Oci,Alibaba,DigitalOcean,Openstack}Provisioner   7 CSP
    ├── ProvisionedCluster            record — base class 가 받는 결과
    └── InstanceOutput, NodeSpec, NodeSpecs, InstanceRole

autoconfigure/
├── ClusterProvisioningAutoConfiguration   @AutoConfiguration + @Bean + @ConditionalOnMissingBean
└── ProvisioningProperties                 @ConfigurationProperties("cluster-provisioning")
```

## 3. 핵심 결정

### 3.1 in-JVM Automation API (Pulumi 1.30.0+)

CLI shell-out 시대의 `PulumiCommandService` 는 폐기. 이유:

- **Go runtime 의존 제거** — `pulumi` binary 의 language host 가 Java SDK 안 `ProvisionerOrchestrator`
  를 invoke. `infra/pulumi/` (Go program) 디렉토리 통째로 삭제.
- **Event stream 풍부도** — `EngineEvent` 가 step-level detail 노출 (raw `--json` 파싱 대비 type-safe).
- **Inline program** — `LocalWorkspace.createOrSelectStack(program)` 가 stack 별 temp workspace 자동
  생성 → host 가 `infra/pulumi/` mount 불필요.

### 3.2 AbstractKubeadmProvisioner template

7 CSP provisioner 가 80% boilerplate 공유 (TLS keypair 생성, master/worker 분기, 표준 outputs 조립,
SSH command, nodes array). base class 로 lift → 각 provisioner 는 `provisionResources` (네트워크,
인스턴스, extras) 만 책임. 새 output 키 추가 = 7곳 → 1곳.

### 3.3 ClusterSpec.Builder + normalize()

이전 18-arg positional `withDefaults(...)` 는 순서 실수 시 silent bug. Builder 패턴 + `normalize()`
fluent API. `Defaults.applyProviderDefaults` 는 `ProviderDefaults` table 기반 — 새 CSP 추가 = entry 1줄.

### 3.4 Package 분리 — api/internal/program

5 packages (automation/ + core/ + service/ + program/ + autoconfigure/) → 4 (api/ + internal/ +
program/ + autoconfigure/). interface (host import 대상) 와 impl (host 직접 import X) 분리.

### 3.5 CSP credential isolation

state backend (RustFS) 와 CSP provider 가 같은 env namespace (AWS_*) 공유 → 충돌. 해결: env 를
Pulumi stack config (secret) 로 변환 + process env 에서 strip. `CspCredentialPulumiConfigMapper.MAPPERS`
의 Map registry — 새 CSP 추가 = entry 1줄.

### 3.6 Plugin pre-install

`Dockerfile.pulumi` 가 7 CSP provider plugin 을 `/opt/pulumi-plugins/` 에 pre-install. 첫 provision
시 ~500MB download 대기 회피 (분 단위 → 즉시). 버전은 `build.gradle` 의 `pulumi*Version` 변수와 sync.

## 4. 테스트

- `ProvisionerSmokeTest` — `PulumiTest.withMocks(new SmokeMocks())` 로 7 CSP provisioner 의 wiring
  (resource graph + 표준 output keys) 검증. 실제 CSP API 호출 없이 in-memory.
- `ProvisionEventBusTest` — multicast / null safety / late-subscriber replay.

## 5. 미정 / Follow-up

- HA control-plane (masterCount > 1) — VIP/LB 도입 필요. 현재 masterCount=1 가정.
- Preview step list — Automation API `PreviewResult` 가 step-level detail 미노출. 향후 `onEvent` 의
  `resourcePreEvent` 집계로 보강 가능.
- Contract test 강화 — secret-marked output 검증, dependency graph assertion.
- proxmoxve — Pulumi Java SDK 부재로 본 마이그레이션 시점 미지원.
