# Optional 사용 규칙

> 단일 진실 — Optional 사용 룰 ("신규 코드는 `.orElse(null)` 추가하지 않는다") 의 상세.

## 왜 중요한가

`.orElse(null)` 는 Optional 의 안전성을 즉시 무력화한다. 호출자가 null check 를 잊으면 즉시
NullPointerException → 운영 사고. Audit 2026-06-11 (#6) 기준 `apps/anycloud` 에 19 곳
사용 중이며, 신규 코드 추가 시 정책을 명확히 한다.

## 4 패턴 중 선택

### 1. 값이 반드시 존재해야 한다 — `.orElseThrow()` ✅

가장 흔한 케이스. cluster id 로 lookup 했는데 없으면 비즈니스 에러 (404).

```java
ClusterEntity entity = clusterRepository.findById(clusterId)
        .orElseThrow(() -> new ClusterNotFoundException(clusterId));
```

도메인 예외가 적합 — `IllegalStateException` 보다는 `*NotFoundException` /
`StateConflictException` 처럼 의도가 드러나는 예외.

### 2. 값이 없을 수 있고, 호출자가 분기 처리한다 — `.ifPresent / .map / .orElseGet` ✅

```java
clusterRepository.findById(clusterId).ifPresent(this::doSomething);
return repo.findById(id).map(this::toDto).orElseGet(this::emptyDto);
```

null sentinel 보다 함수형 분기가 의도를 더 잘 드러낸다.

### 3. 메서드 반환 — `Optional<T>` 그대로 반환 ✅

calling 가 nullable 임을 알 수 있도록 시그니처에 노출.

```java
public Optional<ClusterEntity> findCluster(String id) {
    return clusterRepository.findById(id);
}
```

호출자는 위 1 또는 2 패턴 적용. **반환 시점에서 `.orElse(null)` 로 풀어 던지지 말 것.**

### 4. DTO Builder 의 nullable 필드 매핑 — `.orElse(null)` 허용 (예외) ⚠

Optional 의 nullable 필드를 그대로 매핑할 때만 허용. 단, **호출자가 검증 책임을 명시적으로 가져야**
하며, 가급적 `.orElseGet(() -> defaultValue)` 또는 `Optional<T>` 필드 자체로 두는 게 낫다.

```java
// 허용 — record/DTO 의 nullable 필드 매핑.
.vcpu(Optional.ofNullable(info.vCpuInfo()).map(VCpuInfo::defaultVCpus).orElse(null))
```

`AddonSpecResolver`, `AwsVmOptionsProvider` 의 nullable record field 매핑이 본 패턴에 해당.

### 5. Early-return skip 패턴 — `.orElse(null) + null check + return` 허용 (예외) ⚠

"값이 없으면 정상적으로 skip" 이 의도일 때. 즉 throw 가 부적절하고 method-level early return
이 자연스러운 경우. 가드 helper 로 추출하면 의도가 더 명확.

```java
// 허용 — catalog 없으면 skip 이 비즈니스 의도.
AddonCatalogProperties.Entry catalog = addonCatalog.find(catalogId).orElse(null);
if (!hasGroupBindings(catalog)) {
    log.debug("...");
    return;
}
// catalog 사용

// 함수형 대안 — body 가 짧으면 더 명확:
addonCatalog.find(catalogId)
        .filter(this::hasGroupBindings)
        .ifPresent(c -> applyInternal(addon, c));
```

`AddonRbacBindingApplier.apply / cleanup` 이 본 패턴.

## Anti-pattern (작성하지 말 것)

```java
// ❌ .orElse(null) + null check — Optional 의 의미가 사라짐.
ClusterEntity e = repo.findById(id).orElse(null);
if (e == null) throw new ClusterNotFoundException(id);

// ❌ .isPresent() + .get() — Optional 의 함수형 API 무시.
if (opt.isPresent()) {
    return opt.get();
}
return defaultValue;

// ❌ .orElse(eagerExpensiveCall()) — Optional 가 비어있지 않아도 eager 호출됨.
return repo.findById(id).orElse(buildHeavyDefault());  // → .orElseGet(this::buildHeavyDefault)
```

## 마이그레이션 가이드

기존 코드에서 발견 시:

```bash
git grep -n '\.orElse(null)' apps/anycloud/src/main/java
```

각 사례에 대해:
1. **즉시 null check 가 따라오는가** → `.orElseThrow()` 로 변환.
2. **DTO/record nullable field 매핑인가** → 4 패턴 그대로 유지 (검증 후 주석으로 의도 명시).
3. **메서드 return 인가** → 시그니처를 `Optional<T>` 로 바꾸고 호출자 정리.

작업 단위: 1 commit = 1 ~ 5 변환 + 단위 테스트. 한 PR 에 너무 많이 묶지 말 것 (review 부담 + regression
위험). #6 (2026-06-11) 의 첫 batch 는 `AgentBootstrapServiceImpl` 1 곳만.

## CI 강제

추후 — `spotless` ratchet 또는 `errorprone` 룰로 신규 추가 차단 가능. 현재는 review 시 본 문서 참조.
