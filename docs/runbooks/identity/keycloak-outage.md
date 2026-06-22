# Keycloak outage — fallback runbook

Keycloak (또는 다른 OIDC IdP) 가 down 일 때 운영자가 cluster 명령을 어떻게 보내는지. 3-tier
fallback 전략.

## 책임 분리

anycloud 는 **gateway 가 JWT 검증**하는 모델을 default 가정합니다.

```
[운영자] → [Keycloak login → JWT 발급]
            ↓
       [Gateway: JWT 서명 검증 (JWKS), claim → X-User-*/X-Groups header 변환]
            ↓
       [Backend: header 만 trust, OIDC config 없음]
            ↓
       [Cluster Agent: backend 의 impersonation 헤더 → K8s Impersonate-User/Groups]
```

→ Keycloak 가용성에 가장 강하게 의존하는 곳은 **gateway**. backend 와 cluster-agent 는 헤더만 보면 됨.

## 시나리오 별 fallback

### Tier 1 — Routine downtime (분-단위, 자동 회복)

**증상**: Keycloak 잠시 재시작, 새 로그인 일시 불가. 기존 JWT 보유 운영자는 영향 없음 (JWT 가
만료될 때까지).

**해결**: **gateway 의 JWKS cache TTL 늘리기** (12시간 권장).

- gateway 가 Spring Cloud Gateway 라면 `spring.security.oauth2.resourceserver.jwt.jwks-set-uri`
  의 cache 가 default 5분. 12시간으로 늘려도 무리 없음 (public key rotation 은 운영자가 사전
  공지하므로).
- nginx + lua-resty-openidc 라면 `discovery_cache_period = 86400`.
- envoy / oauth2-proxy 도 동등 설정 있음.

**효과**: JWT 검증이 Keycloak 호출 없이 동작. JWT 유효기간 1h + cache 12h → 운영자는 *최대
13시간 동안* outage 인지 못 함.

### Tier 2 — 장기 outage (시간 단위, manual fallback)

**증상**: Keycloak 가 1시간+ down. 기존 JWT 만료된 운영자가 새로 로그인 불가.

**해결**: **break-glass static token**.

backend 의 환경변수 toggle (이미 코드에 존재):
```bash
SECURITY_AUTH_ENABLED=true
SECURITY_AUTH_TOKEN=<32+ char random>
```

→ gateway 없이 backend 직접 접근 가능. 운영자는 다음 URL 로 직접 호출:

```bash
curl -H "Authorization: Bearer ${BREAK_GLASS_TOKEN}" \
     https://anycloud-backend.internal:8888/v1/admin/clusters/{id}/operations
```

**보관 정책 — 운영팀 필수 준수**:
- token 은 ops team 의 password vault (1Password / Vaultwarden) 에 sealed
- 월 1회 rotation
- 사용 시 ops slack 채널에 "actor=break-glass" 마킹된 audit log 자동 알림
- 사용 후 rotation 의무 (한 사람이 사용한 token 은 다른 사람이 알아도 됨 → 잠재 leak)

**효과**: Keycloak 장애와 무관하게 운영자가 cluster 명령 가능.

### Tier 3 — Catastrophic (Keycloak + backend 둘 다 down, K8s 만 살아있음)

**증상**: 가장 곤란한 시점. cluster 안에 의도하지 않은 상태가 있고 anycloud 가 명령 못 보냄.

**해결**: **emergency kubeconfig** 로 K8s 직접 접근.

`ClusterEntity` 의 admin kubeconfig 는 운영팀 vault 에 미리 export 해 보관 (의도된 평문 저장 정책,
`memory/feedback_cluster_secrets_plaintext.md` 참조).

```bash
# 사전 — 운영자가 vault 에서 가져오기
op read "op://ops-vault/cluster-<name>/admin-kubeconfig" > /tmp/emergency.kubeconfig

# K8s 직접 명령
kubectl --kubeconfig=/tmp/emergency.kubeconfig get pods -A
kubectl --kubeconfig=/tmp/emergency.kubeconfig edit clusterrolebinding <name>

# RBAC binding 직접 갱신 (anycloud 우회)
kubectl --kubeconfig=/tmp/emergency.kubeconfig apply -f /tmp/emergency-binding.yaml
```

**보관 정책**:
- 운영 cluster 별 admin kubeconfig 를 vault 에 별도 entry
- cluster 등록 시점에 한 번 export → vault 보관
- anycloud 의 kubeconfig 갱신 (cert rotation) 시 vault 도 같이 갱신 (cert expiry 모니터링과 연결)

## 운영자 절차 요약

| 상황 | 시간 | 사용할 fallback |
|---|---|---|
| Keycloak 5-30분 down (예상) | T+0 | (자동) gateway JWKS cache |
| Keycloak 1시간+ down | T+15min | Tier 2 break-glass token |
| Keycloak + backend 다 down | T+immediate | Tier 3 emergency kubeconfig |
| Cluster cert expired + anycloud 무용 | T+immediate | Tier 3 — 단 kubeconfig 도 expired 면 외부 cert 발급 절차 |

## 사전 준비 (모든 운영팀)

```markdown
1. ops vault 에 다음 항목 보유 확인:
   - [ ] backend 의 break-glass token (각 환경: dev/stg/prod)
   - [ ] cluster 별 admin kubeconfig
   - [ ] gateway 의 JWKS cache TTL ≥ 12h 설정 검증
2. 월 1회 drill:
   - [ ] break-glass token 으로 backend 호출 성공 (10초)
   - [ ] emergency kubeconfig 으로 cluster ping 성공 (10초)
3. break-glass token 사용 시 audit:
   - StaticTokenAuthFilter 가 INFO 로 "actor=gateway" 로깅 (현 동작)
   - 사용 빈도 모니터링은 별도 운영팀 책임
```

## 향후 강화 (별도 PR 검토 중)

- backend 의 `StaticTokenAuthFilter` 에 sensitive endpoint 호출 시 Slack alert
- gateway JWKS cache 설정의 자동 검증 (anycloud 부팅 시 gateway endpoint 의 cache header 확인)
- break-glass token rotation 자동화 (Vault dynamic secret)
