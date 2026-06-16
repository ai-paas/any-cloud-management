# K8s Integration Testing — kind / k3d ephemeral cluster

mock 대신 실 K8s API 로 cluster-agent flow (registration / RBAC binding / addon install) 회귀
검증. 매 test 가 ephemeral K8s cluster (kind / k3d) 띄우고 종료 후 정리.

## 1. 도구 비교

| 도구 | 특징 | 추천 |
|---|---|---|
| **kind** | Docker 안에서 K8s 실행. multi-node 가능. CNCF | ✓ Default 권장 |
| **k3d** | k3s (lightweight K8s) 의 docker wrapper. 더 가벼움 | 단일 node 시 |
| **Testcontainers K3sContainer** | Testcontainers 의 K8s provider | JUnit 통합 best |
| **Minikube** | VM-based — CI 환경 부적합 | local dev 만 |

추천: **Testcontainers K3sContainer** — JUnit 5 + `@Container` annotation 으로 자동 lifecycle.

## 2. Gradle dependency

```gradle
testImplementation 'org.testcontainers:k3s:1.20.1'
```

## 3. JUnit 통합 패턴

`apps/anycloud/src/test/java/com/aipaas/anycloud/domain/cluster/internal/ClusterAgentK8sIntegrationTest.java` (제안):

```java
package com.aipaas.anycloud.domain.cluster.internal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.k3s.K3sContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
@Testcontainers
class ClusterAgentK8sIntegrationTest {

    @Container
    static final K3sContainer k3s = new K3sContainer(
            DockerImageName.parse("rancher/k3s:v1.31.4-k3s1"));

    @DynamicPropertySource
    static void registerKubeconfig(DynamicPropertyRegistry registry) {
        registry.add("anycloud.test.kubeconfig", k3s::getKubeConfigYaml);
    }

    @Test
    void cluster_agent_install_succeeds_on_real_k8s() {
        // 1. k3s 의 kubeconfig 로 cluster 등록
        // 2. cluster-agent helm chart 설치
        // 3. agent pod ready 대기
        // 4. agent 가 backend 에 reverse-tunnel 연결 검증
        // 5. allowlist ConfigMap apply 검증
    }
}
```

## 4. 권장 적용 범위

각 도메인 별 K8s integration test (현재 mock 사용 위치):

| 도메인 | 검증할 흐름 |
|---|---|
| `domain/agent/bootstrap/` | Helm install / ConfigMap apply / pod ready 대기 |
| `domain/cluster/internal/` | kubeconfig 검증 / SA 발급 / RBAC 권한 확인 |
| `domain/kube/internal/` | KindResolver 가 실 GVK 검색 / generic resource list/patch |
| Layer 2 RBAC | ClusterRoleBinding apply + label selector + informer event |
| Layer 2 Observability | PromQL passthrough (in-cluster prometheus mock) |

## 5. CI integration

```yaml
# .github/workflows/ci.yml 에 추가
- name: K8s integration test
  run: ./gradlew :anycloud:test --tests '*K8sIntegrationTest'
  env:
    TESTCONTAINERS_REUSE_ENABLE: 'false'  # CI 마다 fresh cluster
```

K3s container 부팅 ~30-45초. 매 test class 마다 fresh — slow. 같은 cluster 공유 가능 시
`@TestInstance(Lifecycle.PER_CLASS)` + static container reuse.

## 6. 단계적 도입 plan

| Phase | 작업 | Effort |
|---|---|---|
| 1 | K3sContainer dependency + 1 PoC test | 0.5일 |
| 2 | domain/cluster 의 kubeconfig 검증 test 작성 | 1일 |
| 3 | domain/agent 의 install flow test | 2-3일 |
| 4 | RBAC / observability 의 e2e | 1주 |
| 5 | CI 통합 + perf tuning | 0.5일 |

## 7. Trade-off

**장점**:
- Mock 한계 회피 — 실 K8s API quirk 회귀 자동 발견
- 운영 환경 가까운 시뮬레이션

**단점**:
- CI 시간 ↑ (~30-45s × N test class)
- Docker-in-Docker 의 권한 issue (일부 CI runner)
- K8s version 업그레이드 시 K3s container image 갱신 필요

→ critical path 만 K8s integration test, 그 외는 mock 유지가 균형.
