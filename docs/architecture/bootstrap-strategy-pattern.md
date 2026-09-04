# VmCluster Bootstrap Strategy — Pattern Evaluation

`domain/provisioning/bootstrap/providers/` 의 8 CSP × 1 generic × 1 private variant = 10 file
의 응집도 평가 결과.

## 1. 현재 구조

```
VmClusterBootstrapStrategy (interface, 40 LOC)
    ↑ implements
GenericLinuxVmClusterBootstrapStrategy (209 LOC) — 공통 패턴 lift-up
    ├── kubeadm init / join command
    ├── CNI install (calico)
    ├── Ingress install
    ├── waitFor cluster ready
    ├── GPU preparation (nvidia driver pre-install)
    └── addon install
    ↑ extends
AwsVmClusterBootstrapStrategy             (14 LOC, supports() override 만)
AzureVmClusterBootstrapStrategy           (14 LOC)
GcpVmClusterBootstrapStrategy             (14 LOC)
OciVmClusterBootstrapStrategy             (14 LOC)
AlibabaVmClusterBootstrapStrategy         (14 LOC)
DigitalOceanVmClusterBootstrapStrategy    (14 LOC)
OpenStackVmClusterBootstrapStrategy       (14 LOC)
ProxmoxVmClusterBootstrapStrategy         (14 LOC)

```

## 2. 평가 — 응집도 + Template method 적용도

| 항목 | 상태 |
|---|---|
| 공통 패턴 lift-up | ✓ GenericLinux 에 모든 공통 로직 |
| Template method pattern | ✓ 완벽 적용 (extends + supports() override) |
| CSP-specific divergence | 14 LOC 만 — 매우 좁음 (supports() 단일 method) |
| 신규 CSP 추가 비용 | 14 LOC + integration test = ~1-2일 |

## 3. 결론 — 추가 추출 불요

`GenericLinuxVmClusterBootstrapStrategy` 가 이미 모든 공통 로직 보유. 8 CSP 별 strategy 는
`supports(provider)` 의 ProviderId 매칭 외 차이 없음.

향후 CSP 별 divergence 가 등장하는 경우 (e.g., AWS 의 IMDSv2 specific bootstrap, GCP 의
metadata server quirk) — 그 시점에 override method 추가. 현재 lift-up 은 완벽.

## 4. 신규 CSP 추가 가이드

```java
package com.aipaas.anycloud.domain.provisioning.bootstrap.providers;

public class TencentVmClusterBootstrapStrategy extends GenericLinuxVmClusterBootstrapStrategy {

    @Override
    public boolean supports(String provider) {
        return "tencent".equalsIgnoreCase(provider);
    }
    // 끝 — Tencent specific 필요 시 다른 method override
}
```

+ `factory.go` 의 Pulumi 측 provider 추가 + VmOptionsProvider impl + Bruno collection.

## 5. CSP-specific override 가 필요해진다면

다음 시점에 override method 신설:
- **AWS** : IMDSv2 metadata fetch (현재는 generic 사용)
- **Azure** : Managed Identity bootstrap
- **GCP** : Workload Identity 통합
- **OCI** : OCI Compute Instance Principal

→ 매번 1 method override (~20-50 LOC) 로 충분. 전체 strategy class 신설 불요.
