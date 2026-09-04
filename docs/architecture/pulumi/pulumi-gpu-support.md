# Pulumi GPU Cluster 프로비저닝

`hasGpuNodes=true` cluster 생성 시 backend / Pulumi / cluster-observability 가 협력해 완전한
GPU 워크로드 환경을 구성합니다. 운영자는 옵션 하나만 켜면 됩니다.

## 책임 분리

| 컴포넌트 | 책임 |
|---|---|
| **anycloud (backend)** | `GpuFlavorMapper` 가 provider 별 GPU instance type + `enableGpuOperator=true` 자동 주입 |
| **Pulumi (infra)** | GPU instance 워커 노드 프로비저닝 + NVIDIA GPU operator chart 자동 설치 (driver/runtime/device plugin) |
| **cluster-agent + cluster-observability** | 노드 라벨 감지 → `has_gpu_nodes` backfill → `dcgm-exporter` 자동 설치 → GPU dashboard import |

## 자동 주입되는 Pulumi config

운영자가 `hasGpuNodes=true` 만 보내면 backend 가 다음 config 를 Pulumi 측에 전달합니다.

```yaml
config:
  workerInstanceType: g5.xlarge        # provider 별 default (운영자 명시 시 보존)
  enableGpuOperator: "true"            # nvidia-gpu-operator addon 자동 등록
  # GCP 전용
  workerAcceleratorType: nvidia-tesla-t4
  workerAcceleratorCount: "1"
```

운영자가 명시한 값은 **항상 보존됩니다** — 예: 더 큰 GPU 인스턴스 (`p4d.24xlarge`) 또는 다른 accelerator
(`nvidia-tesla-a100`, count=8) 가 가능합니다.

## Provider 별 default instance

| Provider | instance type | GPU |
|---|---|---|
| `aws` | `g5.xlarge` | NVIDIA A10G x1 (24GB) |
| `gcp` | `n1-standard-4` + accelerator `nvidia-tesla-t4` x1 | T4 x1 (16GB) |
| `azure` | `Standard_NC4as_T4_v3` | T4 x1 (16GB) |
| `oci` | `VM.GPU.A10.1` | A10 x1 (24GB) |
| `alibaba` | `ecs.gn6i-c4g1.xlarge` | T4 x1 |
| `digitalocean` / `openstack` / `proxmox` | (운영자 명시 필요) | — |

## 계층별 책임

`enableGpuOperator` 는 Pulumi 가 읽지 않습니다. Pulumi 의 범위는 VM 과 네트워크까지이고, GPU 는
Kubernetes 계층 관심사입니다.

| 계층 | 담당 | 하는 일 |
|---|---|---|
| Pulumi | provisioner | GPU instance type 으로 워커 노드 생성 |
| cloud-init | `KubeadmUserData` | nouveau 블랙리스트 — driver 컨테이너가 nouveau 와 공존할 수 없습니다 |
| addon | `nvidia-gpu-operator` | driver DaemonSet, Container Toolkit, device plugin, GPU Feature Discovery |
| addon | `dcgm-exporter` | GPU 메트릭 노출 |

GPU operator 는 `driver.enabled=true` 로 **컨테이너 드라이버를 직접 관리합니다.** 호스트에 드라이버를
따로 설치하면 driver 파드의 init 컨테이너가 이를 감지해 파드를 종료시키며, NVIDIA 는 둘 중 하나만
쓰라고 명시합니다.

addon 설치는 agent gRPC 를 타므로 **agent 가 연결된 뒤에** 일어납니다. 요청한 addon 이 실패하면
클러스터가 `DEGRADED` 가 되고 조정 루프가 재시도합니다. 자세한 것은
[vmcluster-convergence.md](../vmcluster-convergence.md) 를 참고하세요.

`dcgmExporter` 는 operator 쪽에서 꺼 둡니다 — 별도 `dcgm-exporter` addon 과 중복되기 때문입니다.

## 사용자 시나리오 (운영자 한 번의 호출)

```bash
curl -X POST https://anycloud/v1/clusters -H 'Content-Type: application/json' -d '{
  "source": "vm",
  "clusterName": "ml-prod-01",
  "spec": {
    "provider": "aws",
    "region": "us-west-2",
    "environment": "prod",
    "hasGpuNodes": true,
    "config": {
      "workerCount": "4"
    }
  }
}'
```

내부 흐름은 다음과 같습니다.
1. `GpuFlavorMapper.applyGpuDefaults` → `workerInstanceType=g5.xlarge` + `enableGpuOperator=true` 자동 주입입니다.
2. Pulumi `ml-prod-01` stack up
   - 4 x `g5.xlarge` 워커 노드 프로비저닝
   - kubeadm cluster 구성
   - `nvidia/gpu-operator` 자동 설치 → driver + runtime 준비
   - kubeconfig 반환
3. Agent 자동 설치 → ACTIVE 전환
4. Agent 가 5분 내 GPU 노드를 감지하여 → heartbeat 로 `gpu_node_count=4` 를 보고합니다.
5. Backend 가 `cluster.has_gpu_nodes=true` 를 갱신합니다.
6. `MonitoringAutoInstaller` 가 자동 동작합니다.
   - `kube-prometheus-stack` 설치
   - `dcgm-exporter` 설치입니다 (GPU 메트릭 노출).
   - Grafana `AIPaaS Cluster Overview` + `AIPaaS GPU Overview` dashboard import 입니다.
7. 운영자가 frontend 에서 Grafana 접속 → GPU 사용률이 즉시 보

운영자 추가 개입은 **0회** 입니다.

## 커스텀 시나리오

### 더 큰 GPU instance

```json
"spec": {
  "provider": "aws",
  "hasGpuNodes": true,
  "config": {
    "workerInstanceType": "p4d.24xlarge",
    "workerCount": "2"
  }
}
```

`g5.xlarge` 자동 매핑이 **skip 됩니다** (운영자 명시값을 보존합니다). `enableGpuOperator=true` 는 여전히
자동 주입됩니다.

### GPU operator 자체 비활성 (외부 driver 관리)

```json
"spec": {
  "provider": "aws",
  "hasGpuNodes": true,
  "config": {
    "enableGpuOperator": "false"
  }
}
```

instance 만 생성되고 addon 은 등록되지 않습니다. 운영자가 driver 를 직접 준비해야 합니다.

### GPU 노드 개수 multi

`workerCount` 4 이상 + multi-AZ subnet 설정 시 자동 분산됩니다. Pulumi `ClusterSpec` 의
`SubnetCidrs` 로 control 합니다.

## Pulumi 측 추가 작업 (미구현 시)

만약 Pulumi 의 각 provider 구현체가 `EnableGpuOperator` 를 아직 처리하지 않는다면 다음과 같이 처리합니다.

```go
// 예 — provider/aws/provision.go 안에서
if spec.EnableGpuOperator {
    // 1. NVIDIA GPU operator Helm chart 설치 (kubeadm join 후)
    //    https://docs.nvidia.com/datacenter/cloud-native/gpu-operator/latest/install-gpu-operator.html
    // 2. node label `nvidia.com/gpu.present=true` 자동 추가 (gpu-operator 가 nfd 와 같이 처리)
}
```

provider 별 `*Provisioner` 의 worker bootstrap 단계에
`gpu-operator` 설치 step 추가가 필요합니다 (해당 작업은 Pulumi 영역이며 — 본 문서는 backend 계약만 명시합니다).

## 트러블슈팅

| 증상 | 원인 | 해결 |
|---|---|---|
| `dcgm-exporter` Pod 가 GPU 노드 없는 cluster 에 안 뜸 | nodeSelector 미일치 | node label `nvidia.com/gpu.present=true` 확인 (gpu-operator 가 부여) |
| `has_gpu_nodes=false` 인데 GPU 노드 있음 | agent 의 5분 캐시 미갱신 | agent pod 재시작 또는 `PATCH /v1/clusters/{c}/capabilities` 수동 |
| `workerInstanceType=g5.xlarge` 인데 GPU 사용 불가 | `enableGpuOperator=false` 이거나 addon 설치 실패 | `GET /v1/vms/{name}` 의 `requestedAddons` 에서 사유 확인 |
| Grafana GPU dashboard 메트릭 비어있음 | `dcgm-exporter` 미설치 또는 service monitor 누락 | `kubectl -n monitoring get pods` 확인 |
