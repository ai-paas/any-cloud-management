# Cluster Agent — 외부 배포 가이드

`cluster-agent` 와 `cluster-agent-spring-boot-starter` 를 anycloud backend 외에서도 재사용하기
위한 가이드. **anycloud 가 없거나 DB 가 없는 standalone 환경**, 또는 **자체 backend 와 연동**
시나리오를 모두 다룹니다.

---

## 1. 무엇을 가져가는가

| 컴포넌트 | 무엇 | 외부 사용처 |
|---|---|---|
| `apps/agent/` (Go) | K8s 클러스터 안에서 도는 agent binary | Rancher 같은 in-cluster agent 가 필요한 모든 컨트롤플레인 |
| `libs/cluster-agent-spring-boot-starter/` | gRPC server + JWT + 세션 + PodExec WebSocket | Java/Spring Boot 백엔드가 agent 와 통신할 때 — SPI 2 개만 구현 |
| `libs/.../src/main/proto/agent/v1/` | proto 원본 (jar 에 포함) | Go / Python / TypeScript / Rust 등 자체 backend 구현 |
| `apps/agent/deploy/helm/cluster-agent/` | Helm chart | K8s 클러스터에 agent 배포 |

**최소 의존성**:
- Agent: Go 1.21+, K8s 1.26+
- Starter: Java 21, Spring Boot 3.2.x, grpc 1.x
- DB: **불필요** (standalone) 또는 backend 구현체가 결정 (anycloud 는 MariaDB 사용)

---

## 2. 단일 정책 모델 — ConfigMap = 정답

자동 sync / drift reconciler / backend defaults 인프라는 모두 제거됨.
**ConfigMap (`aipaas-agent-allowlist`) 이 단일 truth**. 모든 정책 변경 경로가 ConfigMap 으로 수렴.

```
operator ──┬── PUT/PATCH /v1/admin/clusters/{c}/agent-policy ──▶ ConfigMap ──┐
           ├── kubectl edit cm aipaas-agent-allowlist        ──▶ ConfigMap ──┤
           ├── helm upgrade --set allowlist.charts=...        ──▶ ConfigMap ──┤
           └── Argo CD App-of-Apps (Git → kustomize/helm)     ──▶ ConfigMap ──┤
                                                                              ▼
                                                              config.Loader.Watch
                                                                              │
                                                                              ▼
                                                                  agent allowlist reload
```

**Helm values 패턴 그대로** — values.yaml 수정 → `helm upgrade` → ConfigMap 갱신 → 정책 반영.

---

## 3. Quick Start

### 3a. Standalone (자체 backend 없이)

```bash
# 1. helm chart 만 배포 — backend 미설정.
helm install cluster-agent ./apps/agent/deploy/helm/cluster-agent \
  --namespace aipaas-system --create-namespace \
  --set agent.backend.grpcAddr=""    # backend 미연결 — reconnect retry only

# 2. ConfigMap 으로 정책 관리
kubectl -n aipaas-system edit configmap aipaas-agent-allowlist
# allowed_charts / allowed_namespaces / allowed_commands 직접 편집 → 즉시 적용
```

이 모드는 **agent 자체의 ConfigMap allowlist + dispatcher 만** 활용. backend 없이도
- Helm install/uninstall API 호출 (자체 클라이언트가 agent 의 gRPC 에 직접 dial)
- LIST_PODS / GET_LOG 등 K8s ops
- 모두 동작합니다.

### 3b. 자체 backend 연동

#### Step 1 — Backend 쪽에 starter 추가

`pom.xml` 또는 `build.gradle`:
```kotlin
dependencies {
    implementation("io.aipaas:cluster-agent-spring-boot-starter:0.1.0")
}
```

#### Step 2 — SPI 1 개 구현

```java
@Component
public class MyAgentIdentityStore implements AgentIdentityStore {
    // identity_token_hash → AgentIdentity 조회 (DB / Redis / etc.)
    // (자세한 메서드는 starter README 참조)
}
```

#### Step 3 — application.yml 설정

```yaml
cluster-agent:
  jwt:
    secret: ${JWT_SECRET}             # HS256 (32B+)
    issuer: my-org-bootstrap
    audience: cluster-agent-registration
    ttl-seconds: 600                  # registration_token TTL (10분)
  identity:
    ttl-days: 60                      # agent identity_token TTL (60일)
```

#### Step 4 — Backend 가 agent 에게 명령 보내기

```java
@Autowired KubeResourceService kubeResourceService;

// Helm install — INSTALL_ADDON 이 allowed_commands 에 있어야 함
kubeResourceService.installAddon(
    "my-cluster-001", "monitoring", "kube-prometheus-stack",
    "prometheus-community", "45.0.0", values, 600);

// Custom command — proto 의 CommandType 그대로 사용
```

#### Step 5 — 정책 변경 (운영자)

```bash
# 옵션 A: 자체 admin endpoint 만들기
curl -X PUT https://my-backend/v1/admin/clusters/my-cluster-001/agent-policy \
  -H 'Authorization: Bearer ...' \
  -d '{"allowedCharts":["prometheus-community/*:0.0.0-99.99.99", "ingress-nginx/*:4.8.0-4.15.1"]}'

# 옵션 B: 직접 ConfigMap edit
kubectl -n aipaas-system edit cm aipaas-agent-allowlist

# 옵션 C: helm upgrade
helm upgrade cluster-agent ./apps/agent/deploy/helm/cluster-agent \
  --set 'allowlist.charts={prometheus-community/*:0.0.0-99.99.99}'
```

세 옵션 모두 ConfigMap 변경 → agent watch → 즉시 reload.

#### Step 6 — Agent 배포

```bash
helm install cluster-agent ./apps/agent/deploy/helm/cluster-agent \
  --namespace aipaas-system --create-namespace \
  --set agent.backend.grpcAddr=my-backend.example.com:9090 \
  --set agent.backend.tls.enabled=true \
  --set agent.backend.tls.caCert="$(cat my-ca.pem)" \
  --set registrationToken="$(curl ... /v1/admin/clusters/my-cluster-001/agent/registration-token)"
```

---

## 4. 보안 체크리스트

| 항목 | 권장 | 비고 |
|---|---|---|
| **Transport TLS** | ✅ enable | `grpc.server.security.enabled=true` + cert-manager 또는 자체 CA — server cert 만 |
| Identity 인증 | ✅ 동작 | 60일 opaque token (32B hex). K8s Secret 영구 저장 |
| Token rotation | ✅ 동작 | 만료 임박 시 agent 가 RotateIdentityToken RPC 자동 호출 |
| Token revocation | ✅ 동작 | `cluster_agent.revoked_at` set → 모든 후속 인증 거부 |
| **Policy (allowlist)** | ⚠ tune | `allowed_commands` — 킬 스위치 (EXEC_POD 등). `allowed_namespaces` — multi-tenant ns 격리. `allowed_charts` — default wildcard, compliance 시 narrow |
| **K8s RBAC** | ✅ scope | `aipaas-agent-core` read-only, `aipaas-agent-installer` 광범위 (helm 의 본질) |
| ConfigMap write 권한 | ✅ 좁힘 | helm chart 의 `allowlist-writer` Role 이 단일 ConfigMap 만 update — kubectl edit 은 별도 cluster-admin 필요 |
| Pod Security | ✅ baseline | `readOnlyRootFilesystem: true` + `runAsNonRoot: 65534` + `/tmp emptyDir` |
| NetworkPolicy | 🛠 권장 | agent → backend gRPC port 만 egress 허용 — chart 외부 |
| Audit | ✅ backend | audit_log 테이블 (PUT/PATCH 호출 시 자동) |

---

## 5. 운영 시나리오

| 시나리오 | 방법 |
|---|---|
| cluster A 에 새 chart 허용 | `PATCH /v1/admin/clusters/A/agent-policy` 또는 `kubectl -n aipaas-system edit cm aipaas-agent-allowlist` |
| Fleet 전체 정책 일괄 적용 | 1) shell script + PATCH loop, 또는 2) Argo CD App-of-Apps 패턴 + Git, 또는 3) `helm upgrade` per-cluster |
| 정책 변경 audit | backend `audit_log` 테이블 (PUT/PATCH 호출 자동 기록) |
| 사고 발생 시 정책 rollback | Git revert → Argo sync, 또는 직전 snapshot PUT |
| 새 cluster 부팅 시 default 정책 | helm chart values 의 `allowlist:` block 이 day-1 ConfigMap seed |
| Agent compromise — 즉시 차단 | `UPDATE cluster_agent SET revoked_at = NOW() WHERE cluster_name = '...'` |
| Agent 가 backend 연결 못 함 | `BACKEND_GRPC_ADDR` 확인 + TLS CA 검증. agent log 의 `runtime stream terminated` 메시지 |
| chart-museum-external 같이 agent 가 모르는 repo 의 chart 설치 | backend 가 INSTALL_ADDON 의 `chart_tarball` 파라미터에 .tgz base64 직접 전달 |

---

## 6. 환경 변수 reference (agent)

| Key | Default | 설명 |
|---|---|---|
| `BACKEND_GRPC_ADDR` | (필수) | `host:port`. 없으면 agent 가 retry loop |
| `BACKEND_GRPC_TLS_ENABLED` | `false` | backend TLS dial |
| `BACKEND_CA_CERT_PEM` | `(empty)` | backend server cert 검증용 CA PEM (inline) |
| `AGENT_NAMESPACE` | `aipaas-system` | agent 가 동작할 namespace |
| `ALLOWLIST_CONFIGMAP` | `aipaas-agent-allowlist` | 정책 ConfigMap 이름 |
| `AGENT_LEADER_ELECTION` | `false` | replicas > 1 시 leader 1개만 backend 연결 |
| `AGENT_LEASE_NAME` | `aipaas-agent-leader` | leader election Lease 이름 |
| `HOME` | `/tmp` | helm SDK cache. `readOnlyRootFilesystem` 호환 |

---

## 7. 인증 모델 (Rancher-style bearer)

```
┌──────────────┐                                  ┌────────────┐
│ Cluster      │ 1. registration_token (10분 JWT) │  Backend   │
│ Agent        │ ───────► AgentBootstrap.Register │            │
│              │ ◄─── agent_identity_token        │  (verify)  │
│              │      (60일 opaque hex, 32B)      │            │
│  K8s Secret  │ 2. 모든 후속 RPC                   │            │
│  영구 저장    │    Bearer <id>                   │            │
│              │ ───────► gRPC stream / commands  │            │
│              │ 3. 만료 20일 전                    │            │
│              │ ───────► RotateIdentityToken     │            │
│              │ ◄─── new identity_token          │            │
└──────────────┘                                  └────────────┘

Transport: TLS (서버 cert 만 — 일반 HTTPS 같음)
Auth:      Bearer identity_token (SHA-256 hash 로 DB lookup)
Storage:   K8s Secret (agent local) + AgentIdentityStore SPI (backend)
Rotation:  만료 20일 전 자동
Revoke:    AgentIdentityStore.updateStatus(REVOKED) 또는 expires_at 단축
```

---

## 8. 자주 하는 실수

- ❌ `BACKEND_GRPC_ADDR` 만 설정하고 TLS 안 켬 → production 에선 금물
- ❌ `allowed_charts: []` 로 비워두면 모든 chart deny (실수). 기본값 wildcard 또는 명시 narrowing
- ❌ ConfigMap 변경 후 적용 안 됨 → agent watch 가 약 1-2 초 내 reload. log 확인 (`allowlist: policy updated`)
- ❌ JWT secret 을 plaintext 로 git commit → ENV var 또는 K8s Secret 으로
- ❌ identity_token K8s Secret 을 backup 에 그대로 노출 → 60일 long-lived. 별도 보호

---

## 9. 더 읽을거리

- `docs/architecture/cluster-agent.md` — 전체 아키텍처
- `libs/cluster-agent-spring-boot-starter/README.md` — Java SDK 상세
- `apps/agent/README.md` — agent build / 환경 변수 / 디버깅
