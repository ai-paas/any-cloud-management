# GPU Cluster Runbook

NVIDIA GPU cluster 의 생성 / 운영 / troubleshoot 절차.

## 1. 생성 흐름

```
[운영자] POST /v1/clusters
  body.source.type        = "vm"
  body.spec.hasGpuNodes   = true
  body.spec.workerInstanceType = "p4d.24xlarge"   # CSP 별 GPU instance type
                                                 # (CSP-agnostic alias 는 향후 도입)
       ↓
[anycloud preflight]
  - GPU instance type 검증 (region 가용성 + quota)
  - estimated cost 표시 (예: $32/hr × N nodes)
       ↓
[Pulumi up] → GPU instance 생성 (8 CSP 별 GPU type)
       ↓
[bootstrap-worker]
  - kubeadm init/join (일반 흐름)
  - nvidia-container-toolkit 만 pre-install (driver 는 operator 가 처리)
  - GenericLinuxVmClusterBootstrapStrategy.gpuPreparationCommand() 호출
       ↓
[cluster registration → cluster-agent install]
       ↓
[GPU stack addon — 자동 또는 운영자 수동]
  - nvidia-gpu-operator (driver / runtime / device plugin / GFD)
  - dcgm-exporter (metric — monitoring addon 와 자동 동반)
       ↓
[GPU operational]
```

## 2. GPU instance type 선택 — CSP 별 가이드

| CSP | Inference (T4/L4/A10) | Training (A100) | Training (H100) |
|---|---|---|---|
| AWS | `g5.xlarge` (A10) | `p4d.24xlarge` (A100 8x) | `p5.48xlarge` (H100 8x) |
| GCP | `g2-standard-4` (L4) | `a2-highgpu-1g` (A100) | `a3-highgpu-8g` (H100 8x) |
| Azure | `Standard_NC6s_v3` (V100) | `Standard_NC24ads_A100_v4` | `Standard_ND96isr_H100_v5` |
| OCI | `VM.GPU3.1` (V100) | `BM.GPU.A100-v2.8` | `BM.GPU.H100.8` |
| Alibaba | `ecs.gn7i-c8g1.2xlarge` (A10) | `ecs.ebmgn7e.32xlarge` (A100) | (미지원) |

→ 비싼 instance — preflight 의 cost preview 확인 후 진행.

## 3. Addon 설치 (현재 — 운영자 수동)

```bash
# Step 1 — nvidia-gpu-operator (driver / runtime)
curl -X POST /v1/clusters/${CLUSTER}/addons \
  -d '{"catalogId":"nvidia-gpu-operator","enabled":true}'

# Step 2 — kube-prometheus-stack (monitoring) → dcgm-exporter 자동 동반
curl -X POST /v1/clusters/${CLUSTER}/addons \
  -d '{"catalogId":"kube-prometheus-stack","enabled":true}'
# → AddonService.ensureGpuExporterCompanion 이 dcgm-exporter 자동 enqueue (cluster.hasGpuNodes=true 라)
```

## 4. 향후 — 자동 enroll (별 PR)

현재 운영자 수동. 향후 `cluster.hasGpuNodes=true` cluster ACTIVE 시 즉시 자동:

```java
// 제안: ClusterRegistrationServiceImpl 의 onActivated() hook
if (Boolean.TRUE.equals(cluster.getHasGpuNodes())) {
    addonService.createIfAbsent(clusterId, "nvidia-gpu-operator");
}
```

→ 운영자 명시 호출 없이 cluster ACTIVE 즉시 gpu-operator install. driver 자동.

## 5. Troubleshooting

### GPU pod 가 `0/N nodes are available: insufficient nvidia.com/gpu`

- 원인: GPU operator 미설치 또는 driver 실패
- 검증:
  ```bash
  kubectl get pods -n gpu-operator
  kubectl logs -n gpu-operator -l app=nvidia-driver-daemonset
  ```
- 해결: gpu-operator addon state 확인 → reinstall

### Driver install 실패 (DaemonSet CrashLoopBackOff)

- 원인 1: kernel header 미존재 (특정 OS image)
- 원인 2: secure boot enabled
- 해결: GPU operator values 의 `driver.useOpenKernelModules: true` 시도 또는 OS image 변경

### DCGM metric 안 보임

- 검증: `kubectl get pods -n monitoring -l app=dcgm-exporter`
- 검증: GPU operator 의 device-plugin 가 GPU advertise 하는지 — `kubectl describe node ${NODE} | grep nvidia.com/gpu`
- 해결: gpu-operator 먼저 ready → dcgm-exporter restart

### GPU 사용 비용 monitoring

- alert: `GpuUtilizationLow` — 30분 이상 utilization < 5% 시. instance downgrade 또는 Spot 검토
- alert: `GpuPowerHigh` — sustained 300W+ . 정상 운영 신호일 수 있지만 cost monitoring

## 6. 비용 최적화

- **Spot instance**: VmCluster spec 의 `useSpot: true` (AWS / Azure / GCP / Alibaba 지원). 30-70% 절감 — 단 capacity 회수 시 종료.
- **Multi-instance GPU (MIG)**: A100/H100 의 single GPU 를 7 부분 sub-instance 로 분할. inference workload 의 cost ↓.
  - GPU operator values: `mig.strategy: "single"` 또는 `mig.strategy: "mixed"`
- **GPU sharing (time-slicing)**: 단일 GPU 를 여러 pod 가 share. inference 의 low-utilization workload 에 적합.
  - GPU operator values: `devicePlugin.config.default: time-slicing`

## 7. 운영 monitoring 권장 dashboard

- nvidia-gpu-operator 의 Grafana dashboard ID: **12239** (NVIDIA DCGM Exporter Dashboard)
- import: `kubectl apply -f <(curl -s https://grafana.com/api/dashboards/12239/revisions/1/download)`
- anycloud Observability sub-feature 의 DashboardLocator 가 자동 검색
