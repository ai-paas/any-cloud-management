# Starter Extension Guide

`cluster-agent-spring-boot-starter` 위에 도메인 특화 functionality 모듈 (Layer 3) 을 추가하는 방법입니다.

> **대상 독자**: 새 starter 모듈을 만드는 개발자입니다 (내부 또는 외부 기여자).
> **선행 학습**: [`apps/agent/docs/external-deployment.md`](../../apps/agent/docs/external-deployment.md),
> [`libs/cluster-agent-spring-boot-starter/QUICKSTART.md`](../../libs/cluster-agent-spring-boot-starter/QUICKSTART.md) 를 참고합니다.

---

## 1. Layered Architecture — 책임 경계

```
┌──────────────────────────────────────────────────────────────────┐
│ Layer 3: 도메인 특화 (외부 모듈이 자유롭게 추가)                       │
│   책임: 특정 도메인의 의미론 + helm chart 자동화 + 메트릭 카탈로그     │
│   예시: cluster-gpu / cluster-storage / cluster-tenant /          │
│         cluster-backup / cluster-network                          │
└──────────────┬───────────────────────────────────────────────────┘
               │ depends on (api transitive)
┌──────────────▼───────────────────────────────────────────────────┐
│ Layer 2: Functionality (현재 1개 존재)                              │
│   책임: 모니터링 / 로깅 / 보안 등 횡단 영역. 여러 cluster 의 같은 데이터  │
│   현재: cluster-agent-observability-spring-boot-starter                  │
└──────────────┬───────────────────────────────────────────────────┘
               │ depends on (api transitive)
┌──────────────▼───────────────────────────────────────────────────┐
│ Layer 1: Foundation                                              │
│   책임: transport + auth + K8s/Helm execution + policy 게이트       │
│   고정: cluster-agent-spring-boot-starter                          │
└──────────────────────────────────────────────────────────────────┘
```

**책임 분리 원칙**은 다음과 같습니다.

| 모듈 layer | 알아야 하는 것 | 알면 안 되는 것 |
|---|---|---|
| Layer 1 (agent) | gRPC / K8s API / Helm SDK / JWT / WebSocket | "metric", "GPU", "backup" 등 도메인 의미 |
| Layer 2 (functionality) | 자기 도메인의 K8s 리소스 / 의미론 | transport — agent 에게 위임 |
| Layer 3 (domain) | 특정 솔루션 (NVIDIA / Velero / Cilium) 의 chart / API 사양 | 다른 도메인의 의미 |

---

## 2. 새 starter 만들기 — Checklist

### 2.1 디렉토리 + Gradle

```
libs/cluster-<domain>-spring-boot-starter/
├── build.gradle
├── README.md
└── src/main/java/io/aipaas/cluster/<domain>/
    ├── autoconfigure/<Domain>AutoConfiguration.java
    └── service/...
```

**`build.gradle`** 표준 템플릿은 다음과 같습니다.
```groovy
plugins {
    id 'java-library'
    id 'com.diffplug.spotless'
}

bootJar { enabled = false }
jar { enabled = true }

dependencies {
    // Layer 1 의존 — transitive 로 consumer 까지 전파.
    api project(':cluster-agent-spring-boot-starter')

    // Spring Boot core
    api 'org.springframework.boot:spring-boot-starter:3.2.5'

    annotationProcessor 'org.springframework.boot:spring-boot-configuration-processor:3.2.5'

    compileOnly 'org.projectlombok:lombok:1.18.34'
    annotationProcessor 'org.projectlombok:lombok:1.18.34'

    testImplementation 'org.springframework.boot:spring-boot-starter-test:3.2.5'
    testCompileOnly 'org.projectlombok:lombok:1.18.34'
    testAnnotationProcessor 'org.projectlombok:lombok:1.18.34'
}

test { useJUnitPlatform(); maxHeapSize = '512m' }
```

**`settings.gradle`** 에 다음을 추가합니다.
```groovy
include 'cluster-<domain>-spring-boot-starter'
project(':cluster-<domain>-spring-boot-starter').projectDir =
    file('libs/cluster-<domain>-spring-boot-starter')
```

### 2.2 Auto-configuration

```java
package io.aipaas.cluster.<domain>.autoconfigure;

import io.aipaas.cluster.agent.runtime.AgentCommandRouter;
import io.aipaas.cluster.agent.runtime.KubeResourceService;
import io.aipaas.cluster.agent.runtime.HelmReleaseService;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@ConditionalOnProperty(name = "cluster-<domain>.enabled", havingValue = "true", matchIfMissing = true)
public class <Domain>AutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public <Domain>Service <domain>Service(
            KubeResourceService kubeResourceService,
            HelmReleaseService helmReleaseService) {
        return new <Domain>Service(kubeResourceService, helmReleaseService);
    }
}
```

**`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`** 에 등록합니다.
```
io.aipaas.cluster.<domain>.autoconfigure.<Domain>AutoConfiguration
```

### 2.3 Service 클래스 — Layer 1 의 stable bean 활용

```java
@RequiredArgsConstructor
public class <Domain>Service {

    /** Layer 1 의 K8s 명령 게이트웨이. agent 가 cluster 안에서 K8s API 호출. */
    private final KubeResourceService kubeResourceService;

    /** Layer 1 의 Helm 명령 게이트웨이. */
    private final HelmReleaseService helmReleaseService;

    /** 도메인 특화 메서드 예시: */
    public InstallationResult installSolution(String clusterId, ...) {
        // Helm install — Layer 1 위임
        return helmReleaseService.installAddon(
                clusterId, namespace, releaseName, repoName, chartVersion, values, 600);
    }

    public List<Resource> listDomainResources(String clusterId, ...) {
        // K8s list — Layer 1 위임
        return kubeResourceService.listResources(clusterId, "MyCRD", namespace, ...);
    }
}
```

---

## 3. Layer 1 의 Stable Contract — 안심하고 의존할 수 있는 API

| Bean / SPI | 종류 | Stability | 용도 |
|---|---|---|---|
| `AgentCommandRouter` | @Bean | **Stable** | gRPC 명령 송신 (low-level) |
| `KubeResourceService` | @Bean | **Stable** | K8s API 호출 (LIST/GET/DELETE/APPLY) |
| `HelmReleaseService` | @Bean | **Stable** | Helm install/uninstall/list |
| `AgentSessionRegistry` | @Bean | **Stable** | 활성 cluster 목록 조회 |
| `AgentHealthService` | @Bean | **Stable** | cluster health 확인 |
| `PodLogStreamService` | @Bean | **Stable** | Pod log streaming |
| `AgentLifecycleListener` | SPI | **Stable** | agent connect/disconnect 이벤트 hook (multi-bean) |
| `AgentIdentityStore` | SPI | **Stable** | identity 영구 저장소 (host 가 제공) |
| `IdempotencyStore` | SPI | **Stable** | JWT jti 1회 사용 (host 가 제공) |

### 사용 안 됨 = unstable

- `io.aipaas.cluster.agent.grpc.*` 내부 — gRPC handler 입니다. 직접 import 하지 않습니다.
- `io.aipaas.cluster.agent.identity.JwtRegistrationTokenService` — backend 자체용입니다.
- `*.internal.*` 패키지 (있다면) — 향후 변경 가능합니다.

규칙은 **위 표의 stable 만 의존합니다**. 다른 클래스 의존이 필요하면 issue 로 contract 승격을 요청합니다.

---

## 4. Proto 확장 — 3 가지 옵션

새 RPC 가 필요한 경우 (agent 가 새 종류 명령을 처리해야 할 때) 선택지는 다음과 같습니다.

### 옵션 ① Agent proto 에 enum 추가 (현재 방식)

```protobuf
// libs/cluster-agent-spring-boot-starter/src/main/proto/agent/v1/runtime.proto
enum CommandType {
  LIST_PODS = 1;
  INSTALL_ADDON = 2;
  QUERY_METRICS = 3;     // ← observability 가 추가
  GPU_PROFILE_LIST = 4;  // ← 새 starter 추가
}
```

**장점**: 가장 단순하며 type-safe 합니다.
**단점**: agent proto 가 모든 모듈의 union 이 되어 layer 분리가 깨집니다.
**적용 시점**: 처음 1-2 개 모듈입니다. **현재 권장됩니다**.

### 옵션 ② 모듈별 proto file + oneof

```protobuf
// libs/cluster-agent-spring-boot-starter/src/main/proto/agent/v1/runtime.proto
message CommandRequest {
  oneof body {
    GenericCommand generic = 1;
    google.protobuf.Any extension = 2;  // 모듈이 자기 proto 로 채움
  }
}
```

```protobuf
// libs/cluster-gpu-spring-boot-starter/src/main/proto/gpu/v1/profile.proto
message GpuProfileListRequest { ... }
message GpuProfileListResponse { ... }
```

**장점**: 모듈별 namespace 가 분리되며 agent proto 가 안정적입니다.
**단점**: oneof / Any unmarshalling 코드 부담이 있습니다. agent 의 dispatcher 가 type byte string 으로 routing 해야 합니다.
**적용 시점**: 모듈 3-4 개 쌓이면 refactor 합니다.

### 옵션 ③ Generic ExecuteCommand + structpb

```protobuf
message ExecuteCommandRequest {
  string command_name = 1;  // "gpu.profileList"
  google.protobuf.Struct params = 2;
}
message ExecuteCommandResponse {
  google.protobuf.Struct result = 3;
}
```

**장점**: agent proto 변경이 없습니다. 모듈이 자기 string command name 을 등록합니다.
**단점**: type-safety 를 잃습니다. compile-time 검증이 없습니다. API doc 의 별도 관리가 필요합니다.
**적용 시점**: Layer 3 모듈 5+ 개 + 빠른 실험 필요 시에 적용합니다.

---

## 5. Functionality 패턴 — 4 가지 정형

새 starter 의 책임 영역은 보통 다음 중 1개 이상입니다.

### 패턴 A — 솔루션 자동 설치 (Helm wrapper)
```java
public InstallResult installCertManager(String clusterId, String version) {
    return helmReleaseService.installAddon(
            clusterId, "cert-manager", "cert-manager",
            "jetstack", version, defaultValues(), 600);
}
```
**예시**: cert-manager, ingress-nginx, prometheus, Velero, cilium 입니다.

### 패턴 B — 메트릭 / 모니터링 데이터 수집
```java
public PromQLResult query(String clusterId, String query) {
    return kubeResourceService.execAgentCommand(
            clusterId, CommandType.QUERY_METRICS, params, 60);
}
```
**예시**: observability (이미 존재), GPU metrics, custom Prometheus exporters 입니다.

### 패턴 C — CRD lifecycle 관리
```java
public List<MyCRD> list(String clusterId) {
    return kubeResourceService.listResources(
            clusterId, "MyCRD", namespace, /* paging */);
}
public void apply(String clusterId, MyCRD spec) {
    kubeResourceService.applyResource(clusterId, namespace, toYaml(spec), force);
}
```
**예시**: GPU MIG profiles (CRD), backup schedule (Velero CRD), Tenant quota 입니다.

### 패턴 D — Lifecycle hook (agent 이벤트 reaction)
```java
@Component
public class GpuDiscoveryListener implements AgentLifecycleListener {
    @Override
    public void onAgentConnected(String clusterName, String agentInstanceId) {
        // cluster connect 시점에 GPU node 자동 detection
        gpuNodeScanner.scanAsync(clusterName);
    }
}
```
**예시**: cluster 등록 시 자동 inventory 수집입니다.

---

## 6. 외부 사용자 contract — 의존성 그래프

```
외부 사용자의 backend
    │
    ├─ depends on ─▶ cluster-<domain>-spring-boot-starter   (Layer 3)
    │                    │
    │                    └─ api ─▶ cluster-agent-spring-boot-starter   (Layer 1)
    │                                  │
    │                                  └─ api ─▶ spring-boot, grpc, ...
    │
    └─ implements ─▶ AgentIdentityStore (SPI, optional — default impl 있음)
```

`api` (gradle) 키워드로 transitive 전파됩니다 → 외부 사용자는 **Layer 3 starter 하나만** dependency 추가하면 됩니다.

---

## 7. 신규 starter 추가 시 — 호환성 책임

새 starter 가 Layer 1 의 contract 만 사용한다면 Layer 1 변경에 영향이 없습니다. 그러나 다음을 유의합니다.

| 변경 종류 | 영향 | 대응 |
|---|---|---|
| Layer 1 의 stable bean 추가 | ✅ Backward compat | 자유롭게 추가 |
| Layer 1 의 stable bean 메서드 추가 | ✅ Backward compat | 자유롭게 추가 |
| Layer 1 의 stable bean 시그니처 변경 | ❌ Breaking | major version bump + migration guide |
| Layer 1 의 proto enum 추가 (옵션 ①) | ✅ Backward compat (numeric ID 보존 시) | 같은 ID 재사용 X |
| Layer 1 의 SPI 메서드 추가 | ⚠ Source-incompatible | default method 사용 |

---

## 8. 예시 — `cluster-gpu-spring-boot-starter` 스케치

```
libs/cluster-gpu-spring-boot-starter/
├── build.gradle
├── README.md
├── QUICKSTART.md
└── src/main/java/io/aipaas/cluster/gpu/
    ├── autoconfigure/
    │   └── ClusterGpuAutoConfiguration.java
    ├── service/
    │   ├── GpuOperatorInstaller.java       (패턴 A — NVIDIA GPU Operator helm install)
    │   ├── GpuNodeInventoryService.java    (패턴 D — agent 등록 시 GPU node scan)
    │   ├── MigProfileService.java          (패턴 C — MIG CRD lifecycle)
    │   └── DcgmMetricsService.java         (패턴 B — DCGM exporter PromQL)
    └── model/
        ├── GpuNode.java
        ├── MigProfile.java
        └── GpuMetric.java
```

이 구조로 외부 사용자 입장은 다음과 같습니다.
```kotlin
dependencies {
    implementation("io.aipaas.cluster:cluster-gpu-spring-boot-starter:0.1.0")
    // cluster-agent-spring-boot-starter 는 transitive 로 자동 포함
}
```

```yaml
cluster-agent:
  jwt:
    secret: ${JWT_SECRET}
cluster-gpu:
  enabled: true
  dcgm-exporter-version: "3.5.0"
```

```java
@Autowired GpuOperatorInstaller installer;
installer.install(clusterId);
```

---

## 9. anti-patterns — 피해야 할 것

| 안티패턴 | 왜 나쁜가 | 대안 |
|---|---|---|
| Layer 3 가 `io.aipaas.cluster.agent.grpc.*` 직접 import | private API 침범 — agent 변경 시 깨짐 | `KubeResourceService` / `HelmReleaseService` stable bean 사용 |
| Layer 3 starter 가 자체 gRPC stream 따로 열기 | duplicate connection — 클러스터당 N 개 stream 증가, 인증/세션 분리 부담 | agent 의 reverse-tunnel 위에 RPC 추가 |
| Layer 3 가 agent ConfigMap 의 `allowed_commands` 자동 수정 | 정책 ownership 충돌 — kubectl edit / GitOps 와 race | Layer 3 starter README 에 "본 모듈 사용하려면 allowed_commands 에 X 추가 필요" 명시. 운영자가 의식적 결정 |
| 모든 Layer 3 starter 가 같은 `AgentLifecycleListener` 빈 충돌 | Spring 은 같은 type bean 여러개 OK 지만 순서 미정 → 부수효과 race | `@Order` 명시 또는 도메인별 listener 분리 |
| Layer 3 가 backend 의 DB (anycloud 의 cluster_agent 테이블 등) 접근 | tight coupling — 외부 재사용 불가 | Layer 1 의 `AgentSessionRegistry` / `AgentHealthService` 통해서만 cluster 정보 |

---

## 10. 발견된 design opportunity — 더 깔끔할 수 있는 부분

향후 개선 항목은 별 sprint 로 진행 — trigger 발생 시 design doc 신설 후 작업.
