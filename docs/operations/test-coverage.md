# Test Coverage — 현황 / 우선순위 / 로드맵

Jacoco INSTRUCTION 기준입니다. 측정 대상: `anycloud` + `cluster-agent-spring-boot-starter` + `cluster-agent-features-spring-boot-starter` 입니다. 제외: 생성 코드 (proto stub `**/gen/agent/v1/**`, `com/aipaas/anycloud/agent/v1/**`), DTO (`**/dto/**`, `**/*Dto*`) 입니다.

## 현황 (baseline)

| Module | INSTRUCTION | BRANCH | LINE | METHOD | CLASS |
|--------|------------:|-------:|-----:|-------:|------:|
| anycloud | **19.9%** | 15.3% | 19.6% | 19.9% | 29.1% |
| cluster-agent-spring-boot-starter | **32.5%** | 23.9% | 30.8% | 30.9% | 44.8% |
| cluster-agent-features-spring-boot-starter | **56.8%** | 46.6% | 58.2% | 57.5% | 66.7% |

운영 게이트: `jacocoTestCoverageVerification` — INSTRUCTION ≥ 18% (현재 anycloud 가 최저 — conservative baseline) 입니다. 매 sprint +5%p 상향, 목표는 70% 입니다.

## 실행

```bash
# 1. Report 생성 (HTML + XML)
./gradlew :anycloud:jacocoTestReport \
          :cluster-agent-spring-boot-starter:jacocoTestReport \
          :cluster-agent-features-spring-boot-starter:jacocoTestReport

# 2. HTML 열기
open apps/anycloud/build/reports/jacoco/test/html/index.html
open libs/cluster-agent-spring-boot-starter/build/reports/jacoco/test/html/index.html
open libs/cluster-agent-features-spring-boot-starter/build/reports/jacoco/test/html/index.html

# 3. CI gate
./gradlew jacocoTestCoverageVerification
```

## anycloud — Gap 분석 (Top 10 worst, missed instructions 기준)

| Package | Missed | 현재 % | 우선순위 | 사유 |
|---------|-------:|------:|---------|------|
| `domain/vmoptions/providers` | 7220 | 0.8% | **P1 quick-win** | 7개 CSP provider (AWS/Azure/GCP/OCI/Proxmox/Alibaba/OpenStack/DO) 가 모두 0%. 순수 transformation — data-driven 테스트 쉬움 |
| `controller/v1` | 1839 | 50.5% | P2 | MockMvc 로 endpoint 단위 보강. 절반은 이미 cover |
| `domain/cluster/impl` | 1826 | 0.0% | **P4 defer** | `UnifiedClusterServiceImpl` 제거 예정 — 투자 X |
| `domain/provisioning/workflow/impl` | 1654 | 0.0% | P3 | 비동기 workflow — 테스트 harness 필요 |
| `domain/chart/impl` | 1444 | 0.0% | **P1 quick-win** | `ChartServiceImpl` — file I/O mock 가능 |
| `domain/provisioning/query/impl` | 1065 | 0.0% | P2 | 단순 query — JPA mock |
| `domain/chart/support` | 1039 | 26.6% | P2 | `ChartParser`, `ChartArchiveFetcher` — pure logic |
| `domain/agent/upgrade` | 1003 | 35.8% | **P1 in-progress** | `FleetUpgradeService` + `AgentUpgradeService` 테스트 존재 — `FleetUpgradeOrchestratorImpl` (0%, 805 missed) 보강 |
| `libs/cluster-provisioning-spring-boot-starter` | 945 | 0.0% | P3 | Pulumi state — IO 의존 큼 |
| `domain/kube/impl` | 812 | 1.1% | P3 | `KubeServiceImpl` — fabric8 mock 필요 |

## 기존 강점 — 회귀 보호 (>= 60%)

```
domain/provisioning                       100.0%  ← interface 만
domain/agent/capabilities              100.0%
domain/operation                       100.0%
domain/kube/support                     98.6%
domain/vmoptions/impl                   92.8%  ← provider 0% 와 대조적
domain/operation/impl                   89.4%
common/error/enums                       86.8%
common/error                             85.4%
domain/cluster/kubeconfig               83.8%
domain/provisioning/bootstrap              70.0%
model/enums                              68.1%
model/provisioning                       64.8%
```

`vmoptions/impl` 92.8% vs `vmoptions/providers` 0.8% — 같은 도메인인데 provider 만 누락되었습니다. 기존 `*VmOptionsProviderTest` 패턴을 복제하면 빠른 win 입니다.

## 권장 로드맵

### Sprint 1 (현재 → +2주)
- **P1.a** `vmoptions/providers` 7개 provider 단위 테스트 — 입력→출력 매핑 검증입니다. 목표: 0.8% → 70%. **예상 +5%p (anycloud 전체)**
- **P1.b** `chart/impl` + `chart/support` — `ChartParser` (533 missed) 부터입니다. 목표: 0% → 60%. **예상 +3%p**
- **P1.c** `agent/upgrade/FleetUpgradeOrchestratorImpl` (805 missed) — 이미 interface 추출이 완료되었습니다. 목표: 0% → 50%. **예상 +1.5%p**
- 게이트 상향: 18% → 25%

### Sprint 2
- **P2.a** `controller/v1` MockMvc 보강 — 50% → 75%. **예상 +2%p**
- **P2.b** `vmcluster/query/impl` — JPA mock 으로 0% → 50%. **예상 +1%p**
- **P2.c** `agent/auth` + `agent/bootstrap` 보강 — 44%/43% → 65%. **예상 +1.5%p**
- 게이트 상향: 25% → 35%

### Sprint 3+
- P3 packages (workflow, provisioning, kube/impl) — 통합 테스트 harness 도입 후 일괄 처리합니다.
- P4 (cluster/impl 등 deprecated 코드) — 제거 작업과 묶습니다.
- 게이트 상향: 35% → 50% → 70%

## 회피 항목
- `*Dto*`, `**/dto/**` — 단순 record/POJO 입니다. Lombok generated.
- `**/gen/agent/v1/**`, `com/aipaas/anycloud/agent/v1/**` — protoc generated 입니다.
- `UnifiedClusterServiceImpl` (1073 missed) — 제거 예정입니다.

## 참고
- 게이트 설정: 루트 `build.gradle`, `jacocoTestCoverageVerification` block 입니다.
- 제외 패턴 변경 시 `coverageExcludes` 를 수정합니다.
- 모듈 추가 시 `configure(subprojects.findAll { ... })` 의 이름 list 에 추가합니다.
