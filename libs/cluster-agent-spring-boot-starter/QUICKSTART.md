# cluster-agent-spring-boot-starter — Quickstart

**5 분 안에 자체 Spring Boot 백엔드 + cluster-agent 연동 완성**.

DB / 별도 인프라 없이 in-memory default 로 즉시 동작. K8s cluster 안의 agent 가 본 backend 와
gRPC 로 통신하고, PodExec WebSocket / Helm install / K8s 조작 등 모든 명령 라우팅 가능.

---

## 0. 무엇을 만드는가

```
┌──────────────────┐        Bearer + TLS gRPC         ┌──────────────────┐
│ Your Spring Boot │ ────────────────────────────────▶│ K8s 클러스터 안의   │
│ Backend (이 가이드) │ ◀──── reverse stream ─────────── │ Cluster Agent     │
│                  │                                   │ (apps/agent)      │
│  - JWT 발급       │                                   │                  │
│  - gRPC server   │                                   │  - K8s API 호출   │
│  - PodExec WS    │                                   │  - Helm install  │
└──────────────────┘                                   └──────────────────┘
```

Backend 는 **단 3 가지** 만 책임:
1. JWT 발급 (registration_token) — REST endpoint
2. agent 한테 명령 dispatch (KubeResourceService / HelmReleaseService 사용)
3. PodExec WebSocket bridge (자동)

---

## 1. 의존성 추가

`build.gradle.kts`:
```kotlin
dependencies {
    implementation("io.aipaas.cluster:cluster-agent-spring-boot-starter:0.1.0")
    implementation("org.springframework.boot:spring-boot-starter-web:3.2.5")
    implementation("net.devh:grpc-server-spring-boot-starter:3.1.0.RELEASE")
}
```

또는 `pom.xml`:
```xml
<dependency>
    <groupId>io.aipaas.cluster</groupId>
    <artifactId>cluster-agent-spring-boot-starter</artifactId>
    <version>0.1.0</version>
</dependency>
<!-- (+ spring-boot-starter-web, grpc-server-spring-boot-starter) -->
```

---

## 2. application.yml — 최소 설정

```yaml
spring:
  application:
    name: my-cluster-backend

# gRPC server — net.devh 자동 설정
grpc:
  server:
    port: 9090

# Cluster Agent starter — 필수 = JWT secret 만. 나머지는 default.
cluster-agent:
  jwt:
    secret: ${JWT_SECRET:dev-only-secret-min-32-bytes-replace-in-prod}
    issuer: my-org-bootstrap
    audience: cluster-agent-registration
    ttl-seconds: 600          # registration_token 만료 (10분)
  identity:
    ttl-days: 60              # agent identity_token 만료
```

**필수 환경변수**:
```bash
export JWT_SECRET="$(openssl rand -base64 48)"   # production 에선 vault 또는 K8s Secret
```

---

## 3. Spring Boot main class (5 줄)

```java
package com.example.mybackend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class MyClusterBackendApp {
    public static void main(String[] args) {
        SpringApplication.run(MyClusterBackendApp.class, args);
    }
}
```

**이게 끝**. starter 의 auto-configuration 이:
- gRPC server (`AgentBootstrap.Register`, `AgentRuntime.Stream`, PodExec stream) 자동 등록
- WebSocket handler (`/v1/clusters/{c}/pods/{ns}/{pod}/exec`) 자동 등록
- In-memory `AgentIdentityStore` + `IdempotencyStore` 활성 (zero-config)
- JWT 발급 / 검증 / agent 인증 모두 자동

---

## 4. Registration token 발급 endpoint (operator 가 호출)

agent 를 새 cluster 에 배포하려면 backend 가 발급한 short-lived JWT 가 필요. 표준 REST endpoint
하나면 됨:

```java
package com.example.mybackend;

import io.aipaas.cluster.agent.identity.JwtRegistrationTokenService;
import io.aipaas.cluster.agent.identity.JwtRegistrationTokenService.IssuedToken;
import org.springframework.web.bind.annotation.*;
import lombok.RequiredArgsConstructor;
import java.util.Map;

@RestController
@RequestMapping("/v1/admin/clusters")
@RequiredArgsConstructor
public class RegistrationTokenController {

    private final JwtRegistrationTokenService jwtService;

    @PostMapping("/{clusterId}/agent/registration-token")
    public Map<String, Object> issue(@PathVariable String clusterId,
                                     @RequestParam(defaultValue = "MANUAL") String installMode) {
        IssuedToken token = jwtService.issue(clusterId, installMode);
        return Map.of(
                "registrationToken", token.token(),
                "expiresAt", token.expiresAt().toString(),
                "ttlSeconds", token.ttlSeconds());
    }
}
```

---

## 5. Agent 배포 (K8s cluster 안)

```bash
# 1. backend 에서 token 발급
TOKEN=$(curl -X POST http://localhost:8080/v1/admin/clusters/my-cluster-001/agent/registration-token \
  | jq -r .registrationToken)

# 2. agent helm install
helm install cluster-agent \
  /path/to/anycloud/apps/agent/deploy/helm/cluster-agent \
  --namespace aipaas-system --create-namespace \
  --set agent.backend.grpcAddr=host.docker.internal:9090 \
  --set registrationToken=$TOKEN
```

(`host.docker.internal:9090` 는 OrbStack / Docker Desktop K8s 의 host gateway. cloud K8s 면
backend 의 public gRPC endpoint 사용.)

---

## 6. Backend 가 agent 에게 명령 보내기

```java
package com.example.mybackend;

import io.aipaas.cluster.agent.runtime.KubeResourceService;
import io.aipaas.cluster.agent.runtime.HelmReleaseService;
import org.springframework.web.bind.annotation.*;
import lombok.RequiredArgsConstructor;
import java.util.Map;

@RestController
@RequestMapping("/v1/clusters/{clusterId}")
@RequiredArgsConstructor
public class ClusterOpsController {

    private final KubeResourceService kubeResourceService;
    private final HelmReleaseService helmReleaseService;

    // Pod 목록 조회 (agent 가 그 cluster 의 K8s API 호출)
    @GetMapping("/pods")
    public Object listPods(@PathVariable String clusterId,
                          @RequestParam(defaultValue = "default") String namespace) {
        return kubeResourceService.listPods(clusterId, namespace, 60);
    }

    // Helm chart 설치
    @PostMapping("/addons/install")
    public Object install(@PathVariable String clusterId, @RequestBody Map<String, Object> body) {
        return helmReleaseService.installAddon(clusterId,
                (String) body.get("namespace"),
                (String) body.get("releaseName"),
                (String) body.get("repoName"),
                (String) body.get("chartVersion"),
                (Map<String, Object>) body.get("values"),
                /* timeoutSeconds */ 600);
    }
}
```

---

## 7. PodExec WebSocket — 자동 등록

별도 코드 X. starter 가 `ws://localhost:8080/v1/clusters/{cluster}/pods/{ns}/{pod}/exec` 자동 노출.

```javascript
// Frontend / WebSocket client 예
const ws = new WebSocket(`ws://localhost:8080/v1/clusters/my-cluster-001/pods/default/nginx-abc/exec`);
ws.binaryType = "arraybuffer";
ws.onmessage = (e) => terminal.write(new Uint8Array(e.data));
terminal.onData((data) => ws.send(new TextEncoder().encode(data)));
```

xterm.js 와 직접 연결 가능 (binary stdin/stdout + JSON resize frames).

---

## 8. 실행 + 검증

```bash
# 1. Backend 실행
./gradlew bootRun
# 또는 java -jar build/libs/my-cluster-backend.jar

# 2. Agent 배포 + register (Step 5)
# Backend log 에서 다음 메시지 확인:
#   "Agent registered cluster_id=my-cluster-001 instance_id=... expires_at=..."

# 3. 명령 호출
curl http://localhost:8080/v1/clusters/my-cluster-001/pods?namespace=kube-system
# → agent 가 그 cluster 의 K8s API 호출 → pod list 반환
```

---

## 9. 단계별 production 강화

가이드는 in-memory default 로 시작. production 으로 가면서 단계적 강화:

### Step A — JWT secret 외부화
```yaml
cluster-agent:
  jwt:
    secret: ${JWT_SECRET}  # K8s Secret 또는 Vault 에서 주입
```

### Step B — Identity persistence (DB-backed)
```java
@Component
public class JpaAgentIdentityStore implements AgentIdentityStore {
    // JPA repository 위 구현
    // (자세한 메서드는 starter 의 AgentIdentityStore.java 참조)
}
```
→ `@ConditionalOnMissingBean` 으로 in-memory default 자동 비활성.

### Step C — Idempotency persistence
```java
@Component
public class JpaIdempotencyStore implements IdempotencyStore {
    // INSERT IGNORE 패턴 with TTL cleanup batch
}
```

### Step D — TLS server cert
```yaml
grpc:
  server:
    security:
      enabled: true
      certificate-chain: /etc/grpc-tls/server.crt
      private-key: /etc/grpc-tls/server.key
```

Agent 측: `BACKEND_GRPC_TLS_ENABLED=true` + `BACKEND_CA_CERT_PEM` env.

### Step E — Agent lifecycle hooks (선택)
```java
@Component
public class MyAgentLifecycleListener implements AgentLifecycleListener {
    @Override
    public void onAgentConnected(String clusterName, String agentInstanceId) {
        // metrics emit, audit log, etc.
    }
}
```

---

## 10. 자주 묻는 질문

**Q. AgentIdentityStore / IdempotencyStore 구현 안 해도 되나요?**
A. 네. starter 가 in-memory default 제공 (`InMemoryAgentIdentityStore` / `InMemoryIdempotencyStore`).
dev / PoC / single-instance demo 에 충분. multi-instance HA backend 면 DB-backed 권장.

**Q. agent 가 restart 하면 어떻게 되나요?**
A. K8s Secret 에 identity_token 영구 저장 — restart 후에도 같은 token 으로 인증 (Register 호출
   skip). registration_token (10분 JWT) 없이도 ok.

**Q. cluster_agent_db 같은 RDB 가 꼭 필요한가요?**
A. starter 만으로는 NO (in-memory default 동작). production multi-instance / persistent audit
   필요하면 YES.

**Q. observability 기능은?**
A. cluster-agent starter 위에 build — Prometheus 쿼리 / Grafana ingress URL 노출 / Alert 조회.
   monitoring 기능 원하면 같이 추가. `api 'cluster-agent-spring-boot-starter'` 라 transitive.

**Q. anycloud 본체와 다른 점?**
A. anycloud 는 starter + JPA store + admin UI + helm-repo CRUD + cluster CRUD 등 풀스택.
   본 starter 만 가져가면 그 위에 자기 도메인 logic 자유롭게 build.

---

## 11. 다음 단계

- `apps/agent/docs/external-deployment.md` — agent 배포 + 정책 (allowlist) 운영
- `README.md` — starter 가 제공하는 모든 bean / SPI 상세
- `apps/anycloud/` — full-stack reference impl (단순 example 아님 — production 코드)

문의 / 버그 — GitHub issue.
