# cluster-agent-spring-boot-starter

Kubernetes 클러스터 내부에서 동작하는 **Cluster Agent** 와의 reverse-tunnel 통신, PodExec WebSocket 터미널,
인증, 세션 관리를 한 번에 제공하는 Spring Boot starter 입니다. 의존성 한 줄과 JWT secret 만으로 즉시 동작
하며, SPI 구현은 production 단계에서 교체합니다.

> 5분 내 시작 가이드는 [`QUICKSTART.md`](QUICKSTART.md) 를 참고합니다. Spring Boot main 5줄과 application.yml
> 의 JWT secret 만으로 Hello World 수준의 동작이 가능하며, in-memory default (`InMemoryAgentIdentityStore`,
> `InMemoryIdempotencyStore`) 를 제공해 DB 없이 즉시 실행할 수 있습니다.

## 제공 기능

- **gRPC server** — `AgentRuntime.Stream` (long-lived bidi 명령/heartbeat), `AgentRuntime.PodExec`
  (PodExec stream) 을 자동 등록합니다. `AgentBootstrap.Register` 는 도메인 결합이 크기 때문에 호스트가 직접
  구현합니다.
- **JWT registration token** — Agent 부트스트랩용 1회용 단기 토큰을 발급하고 검증합니다. jti 의 1회 사용은
  SPI 로 추상화되어 있습니다.
- **Agent identity 인증** — Bearer agent_identity_token 을 SHA-256 으로 hash 한 뒤 SPI 를 조회하고 만료 / revoke
  여부를 검증합니다.
- **세션 레지스트리** — `cluster_name` → 활성 stream 매핑을 관리하며, command dispatch 와 pending future 를
  보유합니다.
- **Health 종합 응답** — agent status, stream 연결, heartbeat 신선도를 종합한 결과를 반환합니다.
- **PodExec WebSocket bridge** — `ws://.../v1/clusters/{c}/pods/{ns}/{pod}/exec` 를 자동 등록합니다. binary
  stdin/stdout 와 JSON resize frame, 마지막 `{type:"end"}` 패킷을 처리합니다.
- **Kube / Helm 서비스** — `KubeResourceService` 와 `HelmReleaseService` 가 agent 명령으로 K8s 리소스
  CRUD 와 Helm release 수명주기를 호출합니다.

## 호스트가 구현하는 SPI

```java
// 필수: agent 신원 영구 저장소
public interface AgentIdentityStore {
    Optional<AgentIdentity> findByIdentityTokenHash(String tokenHash);
    List<AgentIdentity> findByClusterName(String clusterName);
    AgentIdentity save(AgentIdentity identity);
    boolean updateStatus(String agentId, AgentStatus status, String errorMessage);
    int updateLastSeen(String clusterName, Instant lastSeenAt, Instant lastK8sApiOkAt);
    AgentIdentity rotateToken(String agentId, String newIdentityTokenHash, Instant newExpiresAt);
}

// 필수: JWT jti 중복 방지 (DB INSERT IGNORE / in-memory / cache 자유)
public interface IdempotencyStore {
    boolean tryLock(String key, Duration ttl);
}

// 선택: lifecycle 이벤트 훅 (multi-bean 등록 가능)
public interface AgentLifecycleListener {
    default void onAgentRegistered(AgentIdentity agent) {}
    default void onStreamConnected(AgentIdentity agent) {}
    default void onStreamDisconnected(String clusterName, String agentInstanceId) {}
    default void onHeartbeat(String clusterName, Heartbeat heartbeat) {}
    default void onAgentEvent(String clusterName, AgentEvent event) {}
    default void onExecSessionStarted(String clusterName, String sessionId, ExecRequest req) {}
    default void onExecSessionEnded(String clusterName, String sessionId, ExecStatus status) {}
}
```

## 최소 사용 예 (in-memory adapter)

```java
// 1. 의존성 추가
//
//    [같은 multi-module 안에서]   implementation project(':cluster-agent-spring-boot-starter')
//
//    [외부 Gradle 프로젝트]
//      repositories { mavenLocal() }     // 또는 사내 Nexus/Artifactory/GitHub Packages
//      dependencies {
//        implementation 'io.aipaas.cluster:cluster-agent-spring-boot-starter:0.1.0'
//      }
//
//    [외부 Maven 프로젝트]
//      <dependency>
//        <groupId>io.aipaas.cluster</groupId>
//        <artifactId>cluster-agent-spring-boot-starter</artifactId>
//        <version>0.1.0</version>
//      </dependency>

// 2. AgentIdentityStore 구현 — 최소 in-memory 예시
@Component
public class InMemoryAgentStore implements AgentIdentityStore {
    private final Map<String, AgentIdentity> byHash = new ConcurrentHashMap<>();
    public Optional<AgentIdentity> findByIdentityTokenHash(String h) { return Optional.ofNullable(byHash.get(h)); }
    public List<AgentIdentity> findByClusterName(String c) { /* ... */ }
    public AgentIdentity save(AgentIdentity i) { byHash.put(i.identityTokenHash(), i); return i; }
    public boolean updateStatus(String id, AgentStatus s, String e) { /* ... */ }
    public int updateLastSeen(String c, Instant s, Instant k) { /* ... */ }
    public AgentIdentity rotateToken(String id, String h, Instant exp) { /* ... */ }
}

// 3. IdempotencyStore — Caffeine 예시
@Component
public class CaffeineIdempotencyStore implements IdempotencyStore {
    private final Cache<String, Boolean> used = Caffeine.newBuilder()
            .expireAfterWrite(30, TimeUnit.MINUTES).build();
    public boolean tryLock(String key, Duration ttl) {
        return used.asMap().putIfAbsent(key, true) == null;
    }
}
```

```yaml
# 4. application.yaml
cluster-agent:
  jwt:
    secret: ${AGENT_JWT_SECRET:32-bytes-or-more-please}
    issuer: my-platform
    audience: cluster-agent-registration
    ttl-seconds: 600
  identity:
    ttl-days: 365
  health:
    heartbeat-staleness-threshold: 90s
  exec:
    bind-timeout: 30s
    websocket-buffer-bytes: 65536
  routing:
    enabled: true
    command-timeout-seconds: 15
```

위 설정만으로 다음 기능이 자동으로 동작합니다.

- gRPC server `:9090` 에서 `AgentRuntime.Stream`, `AgentRuntime.PodExec` listen 합니다.
- WebSocket `/v1/clusters/{c}/pods/{ns}/{pod}/exec` 에서 PodExec 연결을 수락합니다.
- `AgentHealthService` bean 으로 종합 health 를 조회할 수 있습니다.
- `AgentCommandRouter` bean 으로 LIST_PODS / GET_LOG / APPLY_MANIFEST 등을 dispatch 할 수 있습니다.

## 모듈 구조

| 패키지 | 역할 |
|---|---|
| `io.aipaas.cluster.agent.core` | SPI 인터페이스 + 도메인 record + enum |
| `io.aipaas.cluster.agent.identity` | JWT/Auth — TokenHasher, AgentJwtProperties, AgentIdentityAuthenticator, JwtRegistrationTokenService, ImpersonationContext |
| `io.aipaas.cluster.agent.runtime` | AgentSessionRegistry, AgentCommandRouter, AgentHealthService, KubeResourceService, HelmReleaseService, ClusterHealth |
| `io.aipaas.cluster.agent.terminal` | ExecSessionRegistry, ExecBridge, PodExecWebSocketHandler |
| `io.aipaas.cluster.agent.logstream` | LogStreamSessionRegistry, LogStreamBridge, PodLogStreamService |
| `io.aipaas.cluster.agent.grpc` | AgentRuntimeEndpoint, AuthMetadataInterceptor |
| `io.aipaas.cluster.agent.autoconfigure` | ClusterAgentAutoConfiguration, ClusterAgentProperties |

## 호스트 측 책임

본 starter 가 다루지 **않는** 부분은 호스트 애플리케이션이 직접 구현합니다.

- **Cluster 도메인 등록 워크플로** — `AgentBootstrap.Register` RPC 의 비즈니스 로직 (DB upsert, RabbitMQ publish 등) 은 호스트가 담당합니다.
- **Agent 자동 설치** — kubectl apply, Helm install 등의 배포 절차는 호스트가 수행합니다.
- **Frontend 터미널 UI** — xterm.js + WebSocket client 는 호스트의 UI 레이어에 속합니다.

본 starter 는 인프라 계층만 책임지며, 도메인 로직은 호스트가 SPI 또는 직접 구현으로 채웁니다.

## 의존성

- Java 21, Spring Boot 3.2.5
- gRPC 1.65, jjwt 0.12.6, protobuf 3.25
- net.devh grpc-server-spring-boot-starter 3.1.0
- spring-boot-starter-websocket

## 좌표 (Maven coordinates)

| 항목 | 값 |
|---|---|
| groupId | `io.aipaas.cluster` |
| artifactId | `cluster-agent-spring-boot-starter` |
| version | `0.1.0` (또는 latest release tag) |

Publish 되는 artifact 는 다음과 같습니다.

- `cluster-agent-spring-boot-starter-{version}.jar` — main classes + `.proto` 원본 (`agent/v1/*.proto`)
- `cluster-agent-spring-boot-starter-{version}-sources.jar` — IDE source hover 용
- `cluster-agent-spring-boot-starter-{version}-javadoc.jar`
- `.pom` + `.module` (Gradle metadata)

## Build / publish

```bash
# 로컬 maven repository (개발자 머신 ~/.m2/repository) 에 publish — consumer 가 mavenLocal() 로 받습니다.
./gradlew :cluster-agent-spring-boot-starter:publishToMavenLocal

# 원격 repository 에 publish (gradle.properties 또는 -P 로 전달).
./gradlew :cluster-agent-spring-boot-starter:publish \
  -PpublishUrl=https://nexus.example.com/repository/maven-snapshots/ \
  -PpublishUsername="$NEXUS_USER" \
  -PpublishPassword="$NEXUS_PASSWORD"
```

`publishUrl` 이 정의되지 않으면 원격 publish task 는 등록되지 않으므로, 좌표를 잘못 입력하더라도 의도치 않은
publish 가 발생하지 않습니다.

## 외부 consumer 가 받는 것

starter 한 줄 의존성으로 다음을 모두 받으며, 그 외에 추가 라이브러리 의존성을 강제하지 않습니다.

- 자동 등록되는 gRPC server (`net.devh:grpc-server-spring-boot-starter`)
- gRPC + protobuf runtime (1.65 / 3.25 명시)
- spring-boot-starter / starter-websocket / starter-validation (3.2.5 명시)
- jjwt API + impl + jackson 모듈 (registration token JWT)
- jackson-databind (WebSocket text frame)

POM 에 의도적으로 노출되지 않는 의존성은 다음과 같습니다.

- spring-boot-starter-webflux (anycloud 전용, 외부 consumer 가 선택)
- lombok (compileOnly, 컴파일 이후 흔적 없음)
- spring-boot-devtools (developmentOnly)


---

## 프로젝트 컨텍스트, 호환성 (any-cloud-management)

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