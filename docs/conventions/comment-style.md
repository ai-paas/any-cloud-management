# 주석 스타일 가이드

후임자가 **주석만 읽고** 클래스/메서드 역할을 1분 안에 이해할 수 있는 것이 목표입니다.
설계 배경 / 트레이드오프 같은 긴 설명은 코드 옆이 아니라 `docs/architecture/*.md` 로 옮깁니다.

## 룰 4가지

### R1. Javadoc 첫 줄 한 문장 — 필수

```java
/** [주체]: [1줄 역할]. [선택: 1줄 제약/주의]. */
```

- 클래스: 무엇을 책임지는지 적습니다 (예: "Cluster 생성 saga 의 시작점.").
- 메서드: 입출력 행동을 적습니다 (예: "JWT 발급 — issuer/audience 검증을 통과한 cluster_id 에 대해서만.").
- 필드: 설정 의미와 단위를 적습니다 (예: "Heartbeat 간격, seconds.").

### R2. 본문 paragraph 금지 — `<ul>` 3 항목까지

paragraph 형태 (3줄 이상의 산문) 는 코드 옆에 두지 않습니다. 필요하면 다음과 같이 처리합니다.

- **이유 (1줄)**: inline `//` 1줄로 표기합니다.
- **선택지/대안**: `<ul>` 최대 3개로 표기합니다.
- **설계 rationale (>3줄)**: `docs/architecture/<topic>.md` 로 이관하고 Javadoc 에서 `@see` 또는 `{@link}` 로 참조합니다.

### R3. "왜" 가 필요하면 inline `//` 1줄 — paragraph 를 만들지 않습니다

```java
// 잘못: 5줄 Javadoc 블록으로 race condition 설명
saveAndFlush(...);

// 권장: 한 줄로 의도 표시
saveAndFlush(entity); // INSERT IGNORE pattern — DataIntegrityViolation 으로 중복 감지.
```

### R4. 상태 prefix 금지

`Phase 1`, `(C5)`, `(M10)`, `TODO(@person)` 같은 작업번호 prefix 는 commit message / PR 에 두고 코드에는 남기지 않습니다.
상태는 변하지만 코드 주석은 영구히 남기 때문입니다.

## Before / After 예시

### 예시 A — Bean 설정 (`BeanConfig.java`)

**Before** (9줄):
```java
/**
 * RestTemplate — Helm repo / 외부 HTTPS endpoint 호출에 사용. 신뢰 경계:
 * <ol>
 *   <li>JVM 기본 truststore 사용 (cacerts).</li>
 *   <li>`anycloud.http.trust-store-path` 가 설정되면 추가 CA 로 사용 (사설 helm repo 등).</li>
 *   <li>SSL 자체를 비활성화하려면 dev 환경에서 {@code anycloud.http.insecure-tls=true} —
 *       startup 시 warn log 와 함께 fallback.</li>
 * </ol>
 */
```

**After** (1줄):
```java
/** RestTemplate: Helm/외부 HTTPS 호출. truststore 3단계 (cacerts → custom CA → dev insecure). */
```

### 예시 B — 비동기 핸들러 (`AsyncConfig.java`)

**Before** (8줄):
```java
/**
 * @Async 작업의 uncaught exception 처리. Spring 의 {@code SimpleAsyncUncaughtExceptionHandler}
 * 는 SLF4J 가 아니라 {@code System.err} 에만 stack trace 를 출력해 로그 수집기에 잡히지 않는다.
 * 구조화 로깅 + Micrometer counter 로 옵저버빌리티 확보:
 * <pre>
 *   async.exception{class=...,method=...,exception=...}  Counter
 * </pre>
 * Prometheus 알람 권장: {@code increase(async_exception_total[5m]) > 0} 발생 즉시 page.
 */
```

**After** (1줄):
```java
/** @Async uncaught handler: SLF4J 로깅 + Micrometer counter (Spring default 대체). */
```

### 예시 C — 메서드 (`JwtRegistrationTokenService`)

**Before** (6줄):
```java
/**
 * registration_token 검증.
 *
 * <p><b>검증 순서</b>:
 * <ol>
 *   <li>JWT 서명 + 만료 검증 (jjwt)</li>
 *   <li>aud / scope / iss 정확 일치</li>
 *   <li>{@link IdempotencyStore#tryLock} 으로 jti 1회 사용 강제 (replay 차단)</li>
 * </ol>
 */
```

**After** (1줄 + bullets):
```java
/**
 * registration_token 검증.
 * <ul>
 *   <li>JWT 서명/만료 (jjwt)</li>
 *   <li>aud / scope / iss 일치</li>
 *   <li>jti 1회 사용 강제 ({@link IdempotencyStore#tryLock})</li>
 * </ul>
 */
```

## 적용 우선순위

1. **High-density config / autoconfig 파일** (한 commit 으로 처리): BeanConfig, AsyncConfig, ShedLockConfig, ClusterAgentAutoConfiguration, ObservabilityAutoConfiguration, JwtRegistrationTokenService, ObservabilityQueryService 등 8~10 개입니다.
2. **나머지는 touch-while-touching** — 다른 변경이 닿는 파일에서 자연스럽게 정리합니다.
3. **신규 코드는 가이드 강제** — PR 리뷰 체크리스트로 확인합니다.

## PR 리뷰 체크

- [ ] Javadoc 첫 줄 1문장이 있습니까?
- [ ] paragraph (3줄 이상 산문) 가 없습니까?
- [ ] 설계 rationale 은 docs/ 로 분리되었습니까?
- [ ] `Phase X` / `(C5)` 같은 상태 prefix 가 없습니까?

## 예외 — 보존해도 되는 paragraph

- **테스트 시나리오 설명** — 테스트는 의도가 곧 문서입니다 (예: `@DisplayName` 보완).
- **사례별 동작 표** — `<table>` / `<ul>` 형태로 구조화된 것은 paragraph 가 아니라 reference 자료입니다.
- **수학식 / 알고리즘 증명** — 1줄로 잘리면 의미가 손상됩니다.
