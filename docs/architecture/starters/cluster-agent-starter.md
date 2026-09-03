# cluster-agent-spring-boot-starter

> **audience**: starter 자체를 host backend 에 의존성으로 추가하거나 확장하려는 개발자. <br>
> 시스템 전체 runtime topology / 인증 / state machine 은
> [`../cluster-agent.md`](../cluster-agent.md) 참조.

Backend 와 in-cluster cluster-agent 사이의 gRPC bidi reverse-tunnel, Pod exec WebSocket, log streaming,
K8s/Helm 명령 dispatcher 를 한 묶음으로 제공하는 Spring Boot starter 입니다. 호스트는 두 개의 SPI
(`AgentIdentityStore`, `IdempotencyStore`) 를 구현하면 cluster-agent control plane 을 즉시 호스팅할 수
있습니다.

## 구성 (Architecture)

본 starter 는 `io.aipaas.cluster.agent` 하위 7 패키지로 구성됩니다.

| 패키지 | 핵심 component | 역할 |
|---|---|---|
| `core` | `AgentIdentityStore`, `IdempotencyStore`, `AgentLifecycleListener`, `AgentIdentity`, `AgentStatus`, `ExecErrorCode` | SPI 정의 + 도메인 record / enum |
| `identity` | `AgentIdentityAuthenticator`, `JwtRegistrationTokenService`, `SigningKeyResolver`, `TokenHasher`, `ImpersonationContext`, `ThreadLocalImpersonationContext`, `ImpersonationIdentity` | Bearer token 인증, JWT 등록 토큰 발급/검증, K8s Impersonation pass-through |
| `runtime` | `AgentSessionRegistry`, `AgentCommandRouter`, `AgentHealthService`, `KubeResourceService`, `HelmReleaseService`, `AgentPolicySnapshot`, `ClusterHealth` | `cluster_name` 별 세션, command dispatch + future, K8s/Helm 고수준 API |
| `terminal` | `ExecSessionRegistry`, `ExecBridge`, `PodExecWebSocketHandler` | PodExec WebSocket ↔ gRPC bridge |
| `logstream` | `LogStreamSessionRegistry`, `LogStreamBridge`, `PodLogStreamService` | Pod log streaming bridge |
| `grpc` | `AgentRuntimeEndpoint`, `AuthMetadataInterceptor` | gRPC server-side endpoint + metadata 인증 |
| `support` | `InMemoryAgentIdentityStore`, `InMemoryIdempotencyStore` | dev / PoC 용 zero-config default |
| `autoconfigure` | `ClusterAgentAutoConfiguration`, `ClusterAgentProperties` | bean 자동 등록 + `cluster-agent` prefix 설정 |

### gRPC / WebSocket 진입점

- **gRPC** — `AgentRuntimeEndpoint` 가 `AgentRuntime.Stream` (long-lived bidi 명령 / heartbeat /
  agent event) 와 `AgentRuntime.PodExec`, `AgentRuntime.PodLogStream` 을 구현합니다.
  - 인증: `AuthMetadataInterceptor` 가 `authorization: Bearer <agent_identity_token>` 을 추출해
    `AgentIdentityAuthenticator` 로 검증합니다.
  - `AgentBootstrap.Register` RPC 는 starter 가 구현하지 않습니다. 도메인 결합 (DB upsert, RabbitMQ
    publish 등) 이 크기 때문에 호스트가 직접 작성합니다.
- **WebSocket** — `PodExecWebSocketHandler` 가 `/v1/clusters/*/pods/*/*/exec` 경로 (path pattern
  설정 가능) 에서 PodExec 연결을 수락하고, `ExecBridge` 를 통해 gRPC stream 으로 packet 을 relay
  합니다. binary stdin/stdout 와 JSON resize frame, 마지막 `{type:"end"}` 패킷을 처리합니다.
- **K8s/Helm command path** — `KubeResourceService` 와 `HelmReleaseService` 는 `AgentCommandRouter`
  를 거쳐 `AgentSessionRegistry.sendCommand(...)` 로 단일 cluster 에 명령을 보냅니다. 결과는
  `CompletableFuture<CommandResponse>` 로 반환됩니다.

## 의존성 (Dependencies)

build.gradle 기준 주요 좌표는 다음과 같습니다.

| 영역 | 좌표 |
|---|---|
| Java / Spring Boot | Java 21, Spring Boot 3.5.16 |
| gRPC | `io.grpc:grpc-netty-shaded:1.65.1`, `grpc-protobuf:1.65.1`, `grpc-stub:1.65.1` |
| gRPC server starter | `net.devh:grpc-server-spring-boot-starter:3.1.0.RELEASE` |
| Protobuf | `com.google.protobuf:protobuf-java:3.25.5`, `protobuf-java-util:3.25.5` |
| Web / WS | `spring-boot-starter-websocket:3.5.16`, `spring-boot-starter-validation:3.5.16` |
| JWT | `io.jsonwebtoken:jjwt-api:0.12.6` (+ runtimeOnly impl / jackson) |
| Jackson | `jackson-databind` (rootProject `jacksonVersion`) |
| Cache | `com.github.ben-manes.caffeine:caffeine` (`AgentSessionRegistry.pendingByRequest` bounded cache) |
| Metrics | `io.micrometer:micrometer-core` (compileOnly — consumer 가 actuator 추가하면 자동 동작) |

본 starter 는 다른 starter 에 의존하지 않으며, **다른 starter (e.g. `cluster-agent-observability-spring-boot-starter`,
`cluster-agent-backup-spring-boot-starter`) 가 본 모듈 위에 build** 됩니다.

`group = io.aipaas.cluster`, `version = 0.1.0` 으로 publish 됩니다. proto 원본 (`agent/v1/*.proto`) 은
jar 안에 포함되어 외부 consumer 가 다른 언어 client 를 생성할 때 사용할 수 있습니다.

## AutoConfiguration

`ClusterAgentAutoConfiguration` 이 활성화되는 조건과 등록되는 bean 은 다음과 같습니다.

### 활성 조건

- `@ConditionalOnClass(AgentRuntimeEndpoint.class)` — classpath 에 starter 가 있으면 자동 활성.
- `WebSocketConfig` 내부 클래스는 추가로 `@ConditionalOnWebApplication(SERVLET)` 와
  `@ConditionalOnProperty(prefix = "cluster-agent.exec", name = "enabled", matchIfMissing = true)`
  로 보호됩니다. worker mode (web-application-type=none) 나 `cluster-agent.exec.enabled=false` 시
  WebSocket 부트스트랩이 생략됩니다.

### 등록 bean (모두 `@ConditionalOnMissingBean`)

- `Clock clusterAgentClock` — `Clock.systemUTC()`
- `AgentIdentityStore` — default `InMemoryAgentIdentityStore` (호스트 DB-backed bean 등록 시 우선)
- `IdempotencyStore` — default `InMemoryIdempotencyStore`
- `ImpersonationContext` — default `ThreadLocalImpersonationContext`
- `AgentSessionRegistry`, `AgentCommandRouter`, `KubeResourceService`, `HelmReleaseService`,
  `AgentHealthService`
- `AgentIdentityAuthenticator`, `SigningKeyResolver` (default `PropertySigningKeyResolver`),
  `JwtRegistrationTokenService`
- `ExecSessionRegistry`, `LogStreamSessionRegistry`, `PodLogStreamService`, `PodExecWebSocketHandler`
- `AuthMetadataInterceptor`, `AgentRuntimeEndpoint`

`AgentLifecycleListener` 는 `List<AgentLifecycleListener>` 로 inject 되므로 호스트가 여러 bean 을 등록할
수 있습니다.

### Properties — `cluster-agent.*`

`ClusterAgentProperties` (record) 는 record 의 compact constructor 에서 null/0 을 default 로 보정합니다.

| Key | Type | Default | 설명 |
|---|---|---|---|
| `cluster-agent.health.heartbeat-staleness-threshold` | `Duration` | `90s` | heartbeat 신선도 임계값 |
| `cluster-agent.exec.bind-timeout` | `Duration` | `30s` | PodExec gRPC bind 대기 |
| `cluster-agent.exec.websocket-buffer-bytes` | `int` (min 8192) | `65536` | WebSocket binary/text buffer |
| `cluster-agent.exec.websocket-path-pattern` | `String` | `/v1/clusters/*/pods/*/*/exec` | exec WS path |
| `cluster-agent.exec.enabled` | `boolean` | `true` (matchIfMissing) | WebSocket 부트스트랩 kill-switch |
| `cluster-agent.routing.enabled` | `boolean` | `true` | Day-2 K8s ops 의 agent routing 활성 |
| `cluster-agent.routing.command-timeout-seconds` | `int` (min 1) | `15` | 일반 명령 timeout (`apply` 는 starter 측 고정값 사용) |

JWT 관련 설정은 `AgentJwtProperties` 가 별도 prefix 로 정의합니다 (`cluster-agent.jwt.*` — secret,
issuer, audience, ttl-seconds).

## 사용

### Gradle dependency

```gradle
// 같은 multi-module 안
implementation project(':cluster-agent-spring-boot-starter')

// 외부 프로젝트
repositories { mavenLocal() }                  // 또는 사내 Nexus / Artifactory
dependencies {
    implementation 'io.aipaas.cluster:cluster-agent-spring-boot-starter:0.1.0'
}
```

### application.yml

```yaml
cluster-agent:
  jwt:
    secret: ${AGENT_JWT_SECRET:32-bytes-or-more-please}
    issuer: my-platform
    audience: cluster-agent-registration
    ttl-seconds: 600
  health:
    heartbeat-staleness-threshold: 90s
  exec:
    bind-timeout: 30s
    websocket-buffer-bytes: 65536
  routing:
    enabled: true
    command-timeout-seconds: 15
```

### Bean inject + 호출

```java
@RestController
@RequiredArgsConstructor
class ClusterOpsController {
    private final KubeResourceService kube;
    private final HelmReleaseService helm;
    private final AgentHealthService health;

    @GetMapping("/v1/clusters/{c}/pods/{ns}/{pod}/logs")
    public String logs(@PathVariable String c, @PathVariable String ns,
                       @PathVariable String pod, @RequestParam(required = false) String container) {
        return kube.getPodLogs(c, ns, pod, container, 1000, false);
    }

    @PostMapping("/v1/clusters/{c}/manifests")
    public JsonNode apply(@PathVariable String c, @RequestParam String namespace,
                          @RequestBody String manifest) {
        return kube.applyResource(c, namespace, manifest);
    }

    @GetMapping("/v1/clusters/{c}/helm")
    public JsonNode releases(@PathVariable String c, @RequestParam(required = false) String namespace) {
        return helm.listReleases(c, namespace);
    }

    @GetMapping("/v1/clusters/{c}/health")
    public ClusterHealth health(@PathVariable String c) { return health.getHealth(c); }
}
```

K8s Impersonation 을 활성화하려면 호스트가 `ImpersonationContext` 를 자체 구현으로 override 하고,
요청 진입점에서 `ImpersonationIdentity` 를 set 합니다. `AgentCommandRouter` 가 current() 값을 자동으로
CommandRequest 의 impersonate_* 필드에 채워 agent 로 전달합니다.

## 한계 / 확장 점

- **AgentBootstrap.Register 미제공** — 등록 RPC 는 도메인 결합이 크기 때문에 호스트가 직접 구현합니다.
  starter 는 `JwtRegistrationTokenService.verifyAndConsume(token)` 으로 토큰 검증만 지원합니다.
- **In-memory default 의 production 적합성** — `InMemoryAgentIdentityStore` / `InMemoryIdempotencyStore`
  는 dev / PoC 용입니다. multi-instance 운영에서는 DB-backed 구현으로 교체합니다.
- **Multi-instance gateway** — `AgentSessionRegistry` 는 단일 process 단위입니다. 여러 backend 인스턴스가
  같은 cluster 에 연결될 가능성이 있다면 gateway 단에서 cluster_id 기반 sticky session 이 필요합니다
  (`docs/architecture/cluster-agent.md#backend-session-registry §0` 참고).
- **Caffeine 의존성** — 현재 starter 가 직접 의존합니다. 향후 compileOnly + 조건부 wiring 으로 분리
  검토 항목입니다 (build.gradle 주석 참고).
- **Frontend UI 미포함** — xterm.js + WebSocket client 등 터미널 UI 는 호스트의 frontend 가 담당합니다.

본 starter 가 큰 그림에서 어떻게 위치하는지는 [`docs/architecture/cluster-agent.md`](../cluster-agent.md) 를,
관련 다른 starter 는 [`cluster-backup-starter.md`](cluster-backup-starter.md),
[`cluster-observability-starter.md`](cluster-observability-starter.md) 를 참고합니다.
