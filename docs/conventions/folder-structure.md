# Folder Structure Convention

`apps/anycloud/src/main/java/com/aipaas/anycloud/` 의 도메인 패키지 (`domain/{feature}/`) 안에서
type-별 sub-folder 어떻게 organize 하는가.

## 1. 전체 구조

```
domain/{feature}/
│
├── (root: 도메인의 primary entry — 외부에 노출되는 public surface)
│   ├── {Feature}Entity.java          # JPA entity
│   ├── {Feature}Service.java         # 메인 service interface
│   ├── {Feature}Facade.java          # multi-source 통합 facade (있는 경우)
│   ├── {Feature}Repository.java      # Spring Data interface
│   └── {Feature}Properties.java      # @ConfigurationProperties (1-2개일 때만 root)
│
├── api/                              # HTTP boundary type — 외부 contract
│   ├── request/                      # *Request.java (3+ 일 때 분리)
│   ├── response/                     # *Response.java (3+ 일 때 분리)
│   └── (root: View, Dto, 그 외 wire format)
│
├── model/                            # 내부 도메인 value object / enum / Spec / record
│   ├── {Feature}.java                # 도메인 record
│   ├── {Feature}Status.java          # enum
│   ├── {Feature}Spec.java            # value object / sealed interface
│   └── ...
│
├── properties/                       # @ConfigurationProperties (3+ 일 때만 분리)
│
├── mapper/                           # MapStruct interface
│
├── port/                             # SPI port (외부 시스템 통합이 있을 때만)
│
├── web/                              # @RestController
│
└── internal/                         # service impl + helper (package-private 의도)
```

## 2. api/ vs model/ — 핵심 구분

| 폴더 | 정의 | 누가 보는가 |
|---|---|---|
| **`api/`** | HTTP request/response 의 wire format. 외부 contract. | frontend, Bruno, OpenAPI doc, 외부 consumer |
| **`model/`** | service layer 안에서만 의미 갖는 value object. 내부 도메인. | 다른 service, mapper, business logic |

### 판단 기준 (직관)

> **"frontend / 외부 client 가 이 type 의 shape 을 보는가?"**
>
> YES → `api/`
> NO → `model/`

### 차이 표

| 측면 | `api/` | `model/` |
|---|---|---|
| 변경 자유도 | 낮음 (versioning + frontend 영향) | 높음 (내부 refactor 자유) |
| JSON 직렬화 | 필수 (`@JsonProperty` 등) | 무관 |
| suffix 패턴 | `*Request`, `*Response`, `*View`, `*Dto` | suffix 없음 또는 `*Status` (enum), `*Spec` |
| 변경 trigger | API spec 변경, OpenAPI 갱신 | 비즈니스 로직 변경 |

### 데이터 흐름

```
[HTTP Request body]
    ↓ Spring deserialize
api/request/CreateClusterRequest   ← 외부 contract
    ↓ mapper
model/Cluster (또는 model/ClusterSpec)   ← 내부 도메인
    ↓ business logic
{Feature}Entity (JPA)              ← persistence
    ↓ ...
model/Cluster (with state)
    ↓ mapper
api/response/ClusterResponse       ← 외부 contract
    ↓ Spring serialize
[HTTP Response body]
```

→ **api/** = 시스템 경계 (HTTP boundary)
→ **model/** = 시스템 내부 (service 간)

## 3. api/ 안 세분

3+ file 이면 sub-folder 분리:

```
api/
├── request/    # *Request.java
├── response/   # *Response.java
└── (root)      # View, Dto, Page, 그 외 wire format
```

- `*Request` 가 3개 이상 → `api/request/`
- `*Response` 가 3개 이상 → `api/response/`
- 그 미만이면 `api/` root flat

## 4. 도메인별 적용 결정 규칙

| root flat file 수 | 적용 |
|---|---|
| < 10 | sub-folder 도입 안 함 (작은 도메인 — over-engineering 회피) |
| 10-20 | api/ 도입 |
| > 20 | api/ + model/ 모두 도입 |

properties 는 별도 룰:
- 1-2개 → root 유지
- 3+ → `properties/` 분리

mapper 는:
- 1개 → root 유지 가능 (또는 mapper/ — 일관성 위해 권장)
- 2+ → `mapper/` 분리

## 5. cluster 도메인 예시 (적용 후)

```
domain/cluster/
├── ClusterEntity.java                # JPA entity
├── ClusterService.java               # 메인 service interface
├── ClusterFacade.java                # multi-source 통합
├── ClusterProvider.java              # interface (per-source strategy)
├── ClusterConnectivityService.java   # interface
├── ClusterFleetHealthService.java    # interface
├── NodeDebugPodService.java          # interface
├── AgentBootstrapKubeClient.java     # interface
├── WebhookProperties.java            # 1개 properties — root 유지
│
├── api/
│   ├── request/
│   │   ├── CreateClusterRequest.java
│   │   ├── CreateClusterOperationRequest.java
│   │   ├── PatchClusterRequest.java
│   │   ├── PatchClusterCapabilitiesRequest.java
│   │   ├── CreateClusterDto.java     # Dto suffix 도 wire format
│   │   └── UpdateClusterDto.java
│   └── response/
│       ├── ClusterResponse.java
│       ├── UnifiedClusterResponse.java
│       ├── ClusterHealthResponse.java
│       ├── FleetAgentHealthResponse.java
│       └── ClusterRegistrationResponse.java
│
├── model/
│   ├── Cluster.java                  # 도메인 record
│   ├── ClusterStatus.java            # enum
│   ├── ClusterSpec.java              # sealed interface (polymorphic union)
│   ├── RegisteredClusterSpec.java    # ClusterSpec variant
│   ├── VmClusterSpec.java            # ClusterSpec variant
│   └── BootstrapInfo.java            # service → service 전달
│
├── mapper/
│   └── ClusterMapper.java            # MapStruct
│
├── port/
├── web/
├── health/
├── admin/
├── kubeconfig/
└── internal/
```

## 6. package-info.java

각 sub-folder 의 의도를 코드에서 자동 발견 가능하게:

```java
// domain/cluster/api/package-info.java
/**
 * Cluster 도메인의 REST API contract — HTTP request/response wire format.
 *
 * <p>외부 consumer (frontend, Bruno, OpenAPI) 가 직접 사용하는 type. 변경 시 versioning + 외부
 * 영향 검토 필요. 내부 도메인 value object 는 {@link com.aipaas.anycloud.domain.cluster.model}.
 */
package com.aipaas.anycloud.domain.cluster.api;
```

```java
// domain/cluster/model/package-info.java
/**
 * Cluster 도메인의 내부 value object / enum / Spec.
 *
 * <p>service layer 끼리 주고 받는 type. 외부 API 와는 mapper 를 통해 변환. 내부 refactor 자유 —
 * 외부 wire format 영향 없음. wire format 은 {@link com.aipaas.anycloud.domain.cluster.api}.
 */
package com.aipaas.anycloud.domain.cluster.model;
```

## 7. 작은 도메인 (root < 10 flat) — sub-folder 도입 안 함

`backup` (3), `audit` (8), `operation` (7), `kube` (7), `credential` (7) 같은 도메인은 root flat
유지가 더 직관적. 빈 sub-folder 만들면 over-engineering.

규칙: **file 이 늘어나서 root 가 10+ 되면 그때 도입**.

## 8. 신규 도메인 추가 시

- 처음엔 root flat 으로 시작 (file 수 적음).
- 같은 type 4+ 누적되면 sub-folder 도입.

## 9. "god file" 판단 기준 — LOC 가 아닌 응집도

큰 file 이 자동으로 god file 아님. 다음 응집도 신호 평가:

| 신호 | god 아님 (보존) | god (분해 필요) |
|---|---|---|
| 책임 수 | **1개** — file 의 모든 코드가 한 책임 | 여러 무관한 책임 섞임 |
| 변경 trigger | **1가지** — 한 이유로만 변경됨 | 여러 다른 이유로 매번 수정 |
| internal organize | private helper / inner class / section 으로 cluster | 무질서한 method 나열 |
| method cohesion | 모두 같은 state 공유 + 서로 호출 | method 끼리 cohesion 0 |
| PR 별 수정 부분 | 한 단위로 evolve | 매 PR 마다 다른 section |

### 사례 분석 (보존 결정)

다음 큰 file 들은 **응집도 평가 후 보존**:

| File | LOC | 보존 이유 |
|---|---|---|
| `GlobalExceptionHandler` | 532 | 단일 책임 (exception → response mapping table). 분해 시 4 file 순회로 신규 합류자 손해. |
| `VmClusterPreflightServiceImpl` | 555 | 단일 흐름 (preflight orchestration). internal helper 로 organize. 분해 시 5 file 점프. |
| `KubeServiceImpl` | 530 | K8s API surface 자체가 큼 (list/patch/delete/exec/port-forward/apply cohesive wrapper). |
| `OciVmOptionsProvider` (+ 8 CSP) | 300-426 each | CSP-specific 로직 의 inherent complexity. `AbstractVmOptionsProvider` 가 이미 공통 로직 lift-up. 각 CSP file 은 REST signing + response parsing 의 single cohesion. |

### 분해 신호 (다음 2+ 만족 시)

- 변경 이유 다양 (PR 마다 다른 부분 수정)
- import 가 무관 도메인 섞임 (50+ import + 절반 이상 도메인 무관)
- method 간 cohesion 0 (서로 호출 X, state 공유 X)
- test class 가 분리 안 되는 무관 시나리오 그룹

**LOC 임계값 (500 / 1000) 은 도그마. 응집도가 진짜 기준.**
- 결정 애매하면 본 문서 § 4 결정 규칙 따름.
