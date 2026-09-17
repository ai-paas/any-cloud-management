# cluster-provisioning-spring-boot-starter

Multi-cloud VM Kubernetes 클러스터 프로비저닝 Spring Boot starter. Pulumi Automation Java SDK 기반
in-JVM 오케스트레이션. Pulumi binary 는 필요하지만 Go runtime 의존 없고, ProcessBuilder fork 없이
JVM 안에서 stack lifecycle 호출.

## Quick Start

```groovy
implementation project(':cluster-provisioning-spring-boot-starter')
```

```yaml
cluster-provisioning:
  enabled: true
  pulumi:
    state-backend-url: s3://pulumi-state?endpoint=...
    command-timeout: 30m
```

```java
@Autowired ProvisioningService provisioningService;

ProvisioningRequest req = ProvisioningRequest.builder()
    .provider("aws")
    .clusterName("demo")
    .credentialEnvironment(Map.of(
        "AWS_ACCESS_KEY_ID", "...",
        "AWS_SECRET_ACCESS_KEY", "..."))
    .config(Map.of(
        "anycloud-k8s:masterCount", "1",
        "anycloud-k8s:workerCount", "2"))
    .build();

Map<String, Object> outputs = provisioningService.provision(req);
ProvisioningResult typed = provisioningService.typedStackOutputs(
    provisioningService.buildStackName(req), Map.of());
```

## Architecture

패키지는 4개로 정리되어 있다.

- `api/` — host 가 직접 import 하는 public surface.
  - `ProvisioningService` (provision/preview/refresh/destroy/outputs interface)
  - `ProvisioningRequest`, `ProvisioningResult`, `ProvisioningPreview`, `ProvisionEvent`
  - `ExecutionConfig` (SPI port — state backend / passphrase / stack prefix)
  - `api/exception/`: `ProvisioningExecutionException`, `ProvisioningResultValidationException`
- `internal/` — starter 안에서만 쓰는 구현.
  - `AutomationProvisioningService` (ProvisioningService impl, LocalWorkspace + WorkspaceStack)
  - `EngineEventAdapter` (Pulumi EngineEvent → ProvisionEvent)
  - `ProvisionEventBus` (Reactor multicast)
  - `ProvisioningResultMapper` (raw Map → ProvisioningResult + jakarta validation)
  - `CspCredentialPulumiConfigMapper` (env var → stack config key)
- `program/` — Pulumi 프로그램 본체.
  - `ClusterSpec` (Builder + normalize), `ProviderSpec` (CSP 전용 설정), `Defaults`, `DatabaseSpec`,
    `JoinTokens`, `ResourceNames`, `ProviderName`, `K8sConstants`, `KubeadmUserData`
  - `program/yaml/`: `PulumiProgram` (YAML 트리), `YamlRef` (참조/함수), `StandardOutputs` (출력 계약),
    `YamlEmitters` (등록), CSP별 `{Csp}YamlEmitter` — AWS/GCP/Azure/OCI/OpenStack.
- `autoconfigure/` — `ClusterProvisioningAutoConfiguration` + `ProvisioningProperties`.

## 호출 흐름

`ProvisioningService.provision(req)` 가 시작점.

1. `AutomationProvisioningService` 가 `LocalWorkspaceOptions` (envVars + stack config) 구성.
2. `CspCredentialPulumiConfigMapper` 가 credential env 를 secret stack config 로 변환.
3. `YamlProgramAssembler` 가 `Pulumi.yaml` 을 만들어 임시 workDir 에 쓰고 `stack.up()`.
4. `{Csp}YamlEmitter.emit(builder, spec)` — 네트워크, 보안그룹, 인스턴스를 `resources` 에 선언.
5. `StandardOutputs.apply` 가 표준 output (provider/clusterName/masterPublicIp/...nodes) 조립.
6. `ProvisioningResultMapper.map(raw)` 가 host 에 반환할 typed record 로 매핑.
7. lifecycle 중 발생한 EngineEvent 는 `EngineEventAdapter` → `ProvisionEventBus` 로 publish.

## 새 CSP 추가

1. `pulumi package get-schema <provider>@<version>` 으로 타입 토큰과 속성 이름을 확인. 추측하면
   `pulumi preview` 가 자격증명 단계에서 먼저 죽어 오타가 드러나지 않는다.
2. CSP 고유 설정이 있으면 `program/ProviderSpec.java` 에 record 를 추가하고 `from()` 분기에 등록.
3. `program/yaml/<Csp>YamlEmitter.java` 작성 — `ProviderYamlEmitter` 구현, `NodeRefs` 반환.
4. `program/yaml/YamlEmitters.EMITTERS` 에 등록.
5. `program/Defaults.java` 의 `TABLE` 에 `ProviderDefaults` entry 추가.
6. `program/ProviderName.java` 의 alias 분기에 canonical 토큰 추가.
7. `internal/CspCredentialPulumiConfigMapper.MAPPERS` 에 env → stack config 매핑 추가.
8. `Dockerfile.pulumi` 에 `pulumi plugin install resource <csp> <version>` 추가.
9. `<Csp>YamlEmitterTest` 작성 — 타입 토큰, CSP 고유 제약, 필수 config 누락 시 fail-fast.

## Tests

- `{Csp}YamlEmitterTest` — 생성된 YAML 을 파싱해 타입 토큰과 속성을 검증. 실 CSP API 호출 없음.
- `YamlProgramDumpTest` — `ANYCLOUD_DUMP_YAML` 로 실제 `Pulumi.yaml` 을 내보내 CLI 검증에 쓴다.
- `ProvisionEventBusTest` — multicast / null safety / late-subscriber replay.

## License

Apache License 2.0.
