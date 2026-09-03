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
  - `ProvisionerOrchestrator` (inline program, ProviderRegistry dispatch)
  - `ClusterSpec` (Builder + normalize), `Defaults`, `DatabaseSpec`, `JoinTokens`, `ResourceNames`,
    `ProviderName`, `K8sConstants`, `KubeadmUserData`
  - `program/provisioner/`: `ProviderProvisioner` (contract), `ProviderRegistry`,
    `AbstractKubeadmProvisioner` (template), 7개 CSP impl (Aws/Gcp/Azure/Oci/Alibaba/DigitalOcean/
    Openstack), 공용 record (`ProvisionedCluster`, `InstanceOutput`, `NodeSpec`, `InstanceRole`).
- `autoconfigure/` — `ClusterProvisioningAutoConfiguration` + `ProvisioningProperties`.

## 호출 흐름

`ProvisioningService.provision(req)` 가 시작점.

1. `AutomationProvisioningService` 가 `LocalWorkspaceOptions` (envVars + stack config) 구성.
2. `CspCredentialPulumiConfigMapper` 가 credential env 를 secret stack config 로 변환.
3. `LocalWorkspace.createOrSelectStack(ProvisionerOrchestrator)` 로 stack 준비 후 `stack.up()`.
4. `ProvisionerOrchestrator.run(ctx, req)` — `ClusterSpec.load(ctx).normalize()` → registry dispatch.
5. `{Csp}Provisioner.provisionResources(ctx, spec)` — VPC/subnet/SG/instances/extras 생성.
   base class 가 결과 받아 표준 output map (provider/clusterName/masterIp/...nodes) 조립.
6. `ProvisioningResultMapper.map(raw)` 가 host 에 반환할 typed record 로 매핑.
7. lifecycle 중 발생한 EngineEvent 는 `EngineEventAdapter` → `ProvisionEventBus` 로 publish.

## 새 CSP 추가

1. `com.pulumi:<provider>` Maven coordinate 확인.
2. 루트 `build.gradle` 의 `pulumi<Provider>Version` ext 추가 + starter `build.gradle` 의
   `api 'com.pulumi:<provider>:${...}'` 추가.
3. `program/provisioner/<Csp>Provisioner.java` 작성 — `extends AbstractKubeadmProvisioner`,
   `provisionResources()` 만 구현 (네트워크 + 인스턴스 + extras 반환).
4. `program/Defaults.java` 의 `TABLE` 에 `ProviderDefaults` entry 1줄 추가. CSP 고유 필드 (예:
   Azure resource group, OpenStack image/flavor, AWS database) 가 있으면 `applyProviderSpecific`
   switch case 추가.
5. `program/ProviderName.java` 의 alias 분기에 canonical 토큰 추가.
6. `internal/CspCredentialPulumiConfigMapper.MAPPERS` 에 env → stack config 매핑 추가.
7. `autoconfigure/ClusterProvisioningAutoConfiguration.java` 에 `@Bean(name="<csp>Provisioner")` 등록.
8. `Dockerfile.pulumi` 에 `pulumi plugin install resource <csp> <version>` 추가 — image build 시
   pre-cache.
9. `src/test/.../SmokeMocks.java` 에 getZones/getImage/getAvailabilityZones 등 CSP lookup mock 추가.
10. `ProvisionerSmokeTest` 에 새 CSP 케이스 추가.

## Tests

- `ProvisionerSmokeTest` — `PulumiTest.withMocks(new SmokeMocks())` 로 7 CSP provisioner 의 wiring
  (resource graph + 표준 output keys) 을 in-memory 검증. 실 CSP API 호출 없음.
- `ProvisionEventBusTest` — multicast / null safety / late-subscriber replay.

## License

Apache License 2.0.
