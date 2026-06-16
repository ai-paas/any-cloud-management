# K8s Impersonation Pass-through 인증

Gateway 가 검증한 사용자 identity 를 K8s API 까지 전파해 K8s RBAC 를 단일 진실 소스로 사용하는
인증 모델입니다. backend 자체 authorization layer (membership / ABAC) 도입 없이 K8s 네이티브 RBAC 만으로
multi-user / multi-tenant 권한을 분리합니다.

신뢰 root 는 gateway, backend 는 user identity 전달자, K8s 는 권한 평가자입니다.

## 1. 호출 흐름

```
┌──────────┐      ┌──────────┐       ┌───────────────────────┐       ┌──────────┐       ┌───────┐
│ end user │─────►│ gateway  │──────►│ anycloud backend       │──────►│  agent   │──────►│  K8s  │
│  (UI)    │      │ (OIDC/JWT)│      │                       │ gRPC  │  (pod)   │  REST │ API   │
└──────────┘      └──────────┘      │ ImpersonationInterceptor│      └──────────┘       └───────┘
                       │             │  ↓ X-Forwarded-User    │             │              │
                  (검증된 identity   │  ThreadLocalImperson...│             │              │
                   를 헤더로 첨가)    │  ↓                     │             │              │
                                    │  KubeResourceService    │             │              │
                                    │  → AgentCommandRouter   │  (Cmd*)     │              │
                                    │  → CommandRequest 의    │─────────────►│              │
                                    │     impersonate_*  필드 │             │              │
                                    └───────────────────────┘             │              │
                                                                          ▼              │
                                                              rest.Config.Impersonate ───►│
                                                                          │              │
                                                                          ▼      RBAC 평가 │
                                                                          ◄────── 결과 ────│
```

5 layer 는 다음과 같습니다.

| Layer | 책임                                                              | 파일                                                                                  |
| ----- | ----------------------------------------------------------------- | ------------------------------------------------------------------------------------- |
| Gateway | OIDC/JWT 검증 + identity 를 trusted header 로 첨가 (`X-Forwarded-User/Groups/Extra-*`) | 외부 (별도 프로젝트)                                                       |
| Backend | header → `ImpersonationIdentity` → `ThreadLocalImpersonationContext.set` | `apps/anycloud/.../security/ImpersonationInterceptor.java`                       |
| Starter SPI | `ImpersonationContext` interface + default ThreadLocal impl       | `libs/cluster-agent-spring-boot-starter/.../identity/Impersonation*.java`            |
| Starter transport | `AgentCommandRouter.send()` 가 context.current() → CommandRequest.impersonate_* 자동 주입 | `libs/cluster-agent-spring-boot-starter/.../runtime/AgentCommandRouter.java`         |
| Agent | CommandRequest 의 impersonate_* → `k8s.ContextWithImpersonation` → `rest.Config.Impersonate` | `apps/agent/internal/controller/dispatcher.go` + `apps/agent/internal/k8s/client.go` |

## 2. 컴포넌트 세부

### 2.1 proto extension

`libs/cluster-agent-spring-boot-starter/src/main/proto/agent/v1/runtime.proto` 의 CommandRequest 는 다음과 같습니다.

```proto
message CommandRequest {
  CommandType type = 1;
  google.protobuf.Struct params = 2;
  int32 timeout_seconds = 3;

  string impersonate_user = 4;
  repeated string impersonate_groups = 5;
  map<string, ImpersonateExtra> impersonate_extras = 6;
}
message ImpersonateExtra {
  repeated string values = 1;
}
```

backward compat — 빈 fields → admin-equivalent 로 동작합니다 (구 agent 도 무시합니다).

### 2.2 Starter SPI

```java
public interface ImpersonationContext {
    Optional<ImpersonationIdentity> current();
    static ImpersonationContext empty() { return Optional::empty; }
}
public record ImpersonationIdentity(String user, List<String> groups, Map<String, List<String>> extras) { ... }
public class ThreadLocalImpersonationContext implements ImpersonationContext {
    public static void set(ImpersonationIdentity identity) { ... }
    public static void clear() { ... }
    public static <T> T withIdentity(ImpersonationIdentity, Supplier<T>) { ... }
}
```

`ClusterAgentAutoConfiguration` 가 default bean (`ThreadLocalImpersonationContext`) 을 등록합니다. backend 가
자체 구현 (e.g. ContextSnapshot 기반 reactive propagation) 을 등록하면 자동으로 override 됩니다.

### 2.3 Starter transport

`AgentCommandRouter.send()` 가 모든 CommandType 에 대해 동일하게 처리합니다.

```java
private void applyImpersonation(CommandRequest.Builder cmdBuilder, ...) {
    ImpersonationContext ctx = impersonationContextProvider.getIfAvailable();
    if (ctx == null) return;
    Optional<ImpersonationIdentity> currentOpt = ctx.current();
    if (currentOpt.isEmpty()) return;
    ImpersonationIdentity id = currentOpt.get();
    cmdBuilder.setImpersonateUser(id.user());
    if (!id.groups().isEmpty()) cmdBuilder.addAllImpersonateGroups(id.groups());
    for (var e : id.extras().entrySet()) {
        cmdBuilder.putImpersonateExtras(e.getKey(),
            ImpersonateExtra.newBuilder().addAllValues(e.getValue()).build());
    }
}
```

zero-cost when toggle OFF 입니다 (provider 가 empty context 를 반환합니다).

### 2.4 Agent Go

`dispatcher.go.commandContext()` 가 CommandRequest 를 받을 때 impersonate_* → `k8s.Impersonation` →
`ctx` 로 처리합니다.

```go
if user := strings.TrimSpace(cmd.GetImpersonateUser()); user != "" {
    imp := &k8s.Impersonation{User: user, Groups: cmd.GetImpersonateGroups(), Extras: ...}
    ctx = k8s.ContextWithImpersonation(ctx, imp)
}
```

`k8s.realClient` 의 ListPods / GetPodLogs / ListResources / GetResource / ApplyManifest / DeleteResource
가 호출 시점에 `clientsetForCtx(ctx)` / `dynamicForCtx(ctx)` 헬퍼로 impersonating client 를 생성합니다.

```go
func (c *realClient) dynamicForCtx(ctx context.Context) (dynamic.Interface, error) {
    imp := ImpersonationFromContext(ctx)
    if imp.IsZero() {
        return c.dyn, nil
    }
    cfg := rest.CopyConfig(c.restConfig)
    cfg.Impersonate = rest.ImpersonationConfig{
        UserName: imp.User, Groups: imp.Groups, Extra: imp.Extras,
    }
    return dynamic.NewForConfig(cfg)
}
```

성능 측면에서 per-call clientset/dynamic 을 재생성합니다 — config copy + new client 입니다. transport 는 lazy 합니다. 추가 K8s I/O 는
없습니다. hot loop 아닌 user-driven request 에 적합합니다. 추후 LRU cache by user-hash 도입을 검토합니다.

### 2.5 Backend interceptor

`ImpersonationInterceptor` 는 gateway header 3종을 읽어 ThreadLocal 에 set/clear 합니다.

- `X-Forwarded-User` → username 입니다 (필수, 비면 admin-equivalent 입니다).
- `X-Forwarded-Groups` → CSV 또는 multi-value 입니다.
- `X-Forwarded-Extra-<key>` → multi-value extras 입니다.

`WebMvcImpersonationConfig` 가 `@ConditionalOnProperty(security.auth.enabled=true)` 로 활성화됩니다 →
toggle OFF 시 interceptor 가 미등록되어 → ThreadLocal 이 항상 empty → admin-equivalent 로 동작합니다.

### 2.6 Agent RBAC

`apps/agent/deploy/helm/cluster-agent/templates/rbac.yaml` 의 `aipaas-agent-core` ClusterRole 은 다음과 같습니다.

```yaml
- apiGroups: [""]
  resources: ["users", "groups", "serviceaccounts"]
  verbs: ["impersonate"]
- apiGroups: ["authentication.k8s.io"]
  resources: ["userextras/*"]
  verbs: ["impersonate"]
```

사실상 super-admin 입니다 (K8s 의 모든 user 를 흉내 낼 수 있습니다) — gateway 인증을 신뢰 root 로 명시합니다. toggle OFF 환경
에서는 부여만 되고 사용되지 않습니다 (cost zero).

## 3. 운영 가이드

### 3.1 활성화 절차

1. **Gateway 측 설정** — OIDC/JWT 검증 후 사용자 identity 를 trusted header 로 첨가하도록 구성합니다.
   ```
   X-Forwarded-User: alice@example.com
   X-Forwarded-Groups: dev-team,ops-team
   ```
2. **K8s 측 사전 구성** — cluster 운영자가 사용자별 RBAC binding 을 생성합니다. 예시는 다음과 같습니다.
   ```yaml
   apiVersion: rbac.authorization.k8s.io/v1
   kind: ClusterRoleBinding
   metadata:
     name: alice-dev-cluster
   subjects:
   - kind: User
     name: alice@example.com
     apiGroup: rbac.authorization.k8s.io
   - kind: Group
     name: dev-team
     apiGroup: rbac.authorization.k8s.io
   roleRef:
     kind: ClusterRole
     name: view
     apiGroup: rbac.authorization.k8s.io
   ```
3. **Backend 설정** — `security.auth.enabled=true` 를 적용한 후 재기동합니다.
4. **Agent helm upgrade** — RBAC 의 `impersonate` verb 가 추가된 chart 로 upgrade 합니다.
   ```bash
   helm --kube-context <c> upgrade cluster-agent \
     apps/agent/deploy/helm/cluster-agent \
     -n aipaas-system --reuse-values
   ```

### 3.2 검증

```bash
# A) backend interceptor 가 등록되었는지 (log 확인)
kubectl logs deploy/anycloud | grep ImpersonationInterceptor
# 기대: "ImpersonationInterceptor ENABLED — gateway headers ... propagated"

# B) agent SA 의 impersonate 권한
kubectl auth can-i impersonate users --as=system:serviceaccount:aipaas-system:aipaas-agent-core
# 기대: yes

# C) 사용자 RBAC 가 작동하는지 (alice 의 read-only binding 가정)
curl -s -H "X-Forwarded-User: alice@example.com" -H "X-Forwarded-Groups: viewer" \
  "$BASE/v1/clusters/c1/namespaces/default/secrets?pageSize=10" \
  | jaq '.data | {returnedItemCount, degraded, degradedReason}'
# viewer 권한이면 secrets list 거부 → degraded=true, degradedReason="FORBIDDEN"
```

### 3.3 비동기 / system 경로

다음은 ThreadLocal 이 set 되지 않아 자연스럽게 admin-equivalent 로 동작합니다.

- RabbitMQ listener (`cluster.addon.install`) → INSTALL_ADDON 명령은 system action 의도와 일치합니다.
- Scheduled job (cluster ACTIVE 시점 helm_repositories 자동 push, kubeconfig export 등) 입니다.
- Boot-time runner 입니다.

명시적으로 특정 user 권한으로 async 작업하려면 `ThreadLocalImpersonationContext.withIdentity(id, () -> {...})`
로 wrap 합니다.

### 3.4 Limitations

| 항목                              | 내용                                                                                 |
| --------------------------------- | ------------------------------------------------------------------------------------ |
| Agent SA = super-admin            | gateway 우회 (직접 backend ↔ agent gRPC 호출) 시 사용자 user 흉내 가능. mTLS / network policy 로 backend → agent 경로 보호 필수. |
| Performance per-call client 생성  | `dynamic.NewForConfig` + `clientset.NewForConfig` 비용은 micro-second 수준. hot loop 미사용 시 무시 가능. |
| OIDC group → K8s group 매핑 부담  | cluster 운영자가 매번 ClusterRoleBinding 작성 필요. |
| toggle OFF 상태에선 RBAC 작동 안 함 | 모든 사용자가 admin-equivalent. 운영 환경은 toggle ON 권장. |
| K8s `view` 미라벨 CRD              | 일부 CRD operator 가 `aggregate-to-view` 라벨 안 붙임 → `view` ClusterRole 로는 cover 안 됨. cluster 운영자가 명시 binding 필요. |

## 4. 호환성

- **Toggle OFF (default)** — backend interceptor 가 미등록되며, agent CommandRequest 의 `impersonate_*`
  필드는 모두 빈 상태입니다. agent 는 base config 를 그대로 사용합니다 (admin-equivalent).
- **구 agent (impersonate verb 없음) + Toggle ON** — agent 의 K8s API 호출이 `impersonate` 권한
  부족으로 K8s 가 403 을 반환합니다. backend 의 `classifyDegradedCause` 가 `FORBIDDEN` 라벨로 정확히 노출되어 →
  운영자가 RBAC 누락을 진단할 수 있습니다.
- **신 agent (impersonate verb) + Toggle OFF** — RBAC 부여만 되고 invoke 는 되지 않습니다.

## 5. 관련 파일

| 파일                                                                                                | 변경 |
| --------------------------------------------------------------------------------------------------- | ---- |
| `libs/cluster-agent-spring-boot-starter/src/main/proto/agent/v1/runtime.proto`                      | CommandRequest 에 impersonate_* + ImpersonateExtra message |
| `libs/cluster-agent-spring-boot-starter/.../identity/ImpersonationContext.java`                     | SPI interface (신규)                                          |
| `libs/cluster-agent-spring-boot-starter/.../identity/ImpersonationIdentity.java`                    | record (신규)                                                 |
| `libs/cluster-agent-spring-boot-starter/.../identity/ThreadLocalImpersonationContext.java`          | default impl (신규)                                           |
| `libs/cluster-agent-spring-boot-starter/.../autoconfigure/ClusterAgentAutoConfiguration.java`       | default bean 등록                                              |
| `libs/cluster-agent-spring-boot-starter/.../runtime/AgentCommandRouter.java`                        | send() 가 impersonate_* 자동 주입                              |
| `apps/agent/internal/k8s/impersonation.go`                                                          | Impersonation type + ctx helpers (신규)                       |
| `apps/agent/internal/k8s/client.go`                                                                 | clientsetForCtx / dynamicForCtx + 6 method 적용                |
| `apps/agent/internal/controller/dispatcher.go`                                                      | commandContext 가 CommandRequest 의 impersonate_* → ctx attach |
| `apps/anycloud/.../configuration/security/ImpersonationInterceptor.java`                            | gateway header → identity → ThreadLocal (신규)                |
| `apps/anycloud/.../configuration/security/WebMvcImpersonationConfig.java`                           | interceptor 등록 (신규, toggle 연동)                           |
| `apps/agent/deploy/helm/cluster-agent/templates/rbac.yaml`                                          | ClusterRole 에 impersonate verb                                |

