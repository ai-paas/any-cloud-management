# DTO Naming Convention

anycloud backend 의 REST/DTO 명명 표준입니다.

## 1. 표준 명명 패턴

| 카테고리 | Suffix | 위치 | 예시 |
| --- | --- | --- | --- |
| **HTTP request body** | `*Request` | `api/request/` | `ChartDeployRequest`, `AddonCreateRequest` |
| **HTTP response body** | `*Response` | `api/response/` | `ChartDeployResponse`, `OperationStatusResponse` |
| **wire-only DTO (Request/Response 분류 안 맞음)** | suffix 없음 또는 `*Info` | `api/` (root) | `ChartHistoryItem`, `VmOptionRegion`, `ResourceKindInfo` |
| **DB 영속 객체** | `*Entity` | 도메인 root | `ClusterEntity`, `ClusterAddonEntity` |
| **즉시 데이터 캐리어 (record / 내부 value object)** | suffix 없음 | `model/` | `Cluster`, `BootstrapInfo`, `ProvisioningOutput` |
| **Enum / status / spec** | suffix 없음 | `model/` | `AddonType`, `AddonState`, `ClusterSpec` |

> **`*Dto` suffix 사용 회피** — "DTO" 가 모든 wire format 의 generic term 이라 의미가 약함.
> 명확한 suffix (`Request` / `Response`) 사용하거나, 둘 다 안 맞으면 suffix 없는 명사
> (`ChartHistoryItem`, `VmOptionRegion`). 단 legacy `CreateClusterDto` / `UpdateClusterDto`
> 는 `Request` 와 별 의미가 있어 예외 보존.

### Examples ✅

```java
@PostMapping("/charts/{cluster}/install")
public ChartDeployResponse install(@RequestBody ChartDeployRequest req) { ... }

@GetMapping("/clusters/{id}/history")
public List<ChartHistoryEntryDto> history(@PathVariable String id) { ... }
```

### Anti-pattern ❌

```java
// ❌ 일관성 부재 — Request suffix 없음
public ChartDeployAcceptedResponseDto install(@RequestBody ChartDeployDto req) { ... }

// ❌ Response 인데 Dto suffix
public ChartHistoryResponseDto history(...) { ... }
```

## 2. Element / value carrier 예외

다음 분류는 `*Request` / `*Response` rename 대상이 아니라 `*Dto` 그대로 유지하는 element / value carrier 입니다. §1 의 "Records/value objects" 카테고리에 fit.

| File | 분류 | 사유 |
|---|---|---|
| `response/chart/ChartHistoryItemDto.java` | element | response container 안의 history item |
| `response/chart/HelmReleaseResourceRefDto.java` | ref carrier | release resource reference value |
| `response/vmoptions/{Provider,Image,Region,Spec,Config}*Dto.java` (5개) | element | `VmOptionsResponse` 안의 element types |
| `request/cluster/CreateClusterDto.java`, `UpdateClusterDto.java` | internal service transfer | controller @RequestBody 가 아니라 service-layer transfer (KubeconfigParser / RegisteredClusterProvider 가 build → `ClusterService.createCluster(Dto)`). §1 의 "Internal domain objects" 카테고리 |

## 3. Frontend 영향

`@RequestBody` / `@ResponseBody` 의 wire format 은 JSON 이며, class 이름이 wire 에 노출되지 않습니다 (default Jackson). 따라서 다음과 같이 정리됩니다.

- Frontend `axios` / `fetch` 호출에는 영향이 없습니다.
- OpenAPI schema 의 component name 만 변경됩니다 (springdoc-openapi 가 자동 갱신합니다).
- TypeScript codegen 시점에 새 이름이 적용됩니다.

## 4. 호환성 deprecation 전략 (대규모 변경 시)

```java
// grace period — 구 class 가 새 class 의 alias:
@Deprecated(forRemoval = true)
public class ChartDeployDto extends ChartDeployRequest {
    // empty subclass, 동일 field
}
```

→ 점진적 migration 이 안전합니다. grace period 종료 후 삭제합니다.

## 5. 검증 명령

```bash
# DTO 명명 drift 발견:
fd -e java 'Dto\.java$' apps/anycloud/src/main/java | grep -i 'request\|response' | head -10

# Request/Response suffix 없는 controller method param/return:
ast-grep run -p '@PostMapping($_) public $TYPE $METHOD(@RequestBody $PT $P)' apps/anycloud/src/main/java/com/aipaas/anycloud/controller
```

## 6. 관련 doc

- rename 이력: `git log --follow <file>`
