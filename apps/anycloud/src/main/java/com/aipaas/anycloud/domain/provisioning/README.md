# `domain/provisioning/`

VM 인프라 기반 K8s 클러스터의 **프로비저닝 라이프사이클** 을 담당하는 도메인.

## 책임 vs `domain/cluster/`

| | `domain/cluster/` | `domain/provisioning/` (이 폴더) |
|---|---|---|
| **무엇** | 이미 등록된 K8s cluster 의 truth source + 운영 | VM 위에 K8s 클러스터를 **만드는** 과정 |
| **상태** | 등록 후 운영 (kubeconfig 갱신, agent 연결, RBAC, addon, helm) | 생성 → bootstrap → verify → 완료 (→ `cluster/` 로 승격) |
| **의존** | gRPC agent, kubeconfig, K8s client | Pulumi (Go IaC), SSH, kubeadm, RabbitMQ workflow |
| **수단** | direct K8s API call, agent reverse-tunnel | Pulumi CLI subprocess, kubeadm init/join |

## Sub-package 지도

| sub-package | 책임 |
|---|---|
| (root) | `VmCluster` (도메인 모델) + `VmClusterEntity` (JPA) + `VmClusterService` 등 주요 entry point |
| `preflight/` (구 `query/`) | 생성 전 검증: provider 정규화, credential 해결, VM options 검증, cost preview |
| `command/` | 비동기 명령 진입점: provision / scale / destroy 요청 큐잉 |
| `workflow/` | RabbitMQ 기반 state machine: provision → bootstrap → verify → ready |
| `bootstrap/` | kubeadm init/join 실행 + CSP 별 BootstrapStrategy (각 9 CSP 의 user-data) |
| `scale/` | worker 수 증감 (Pulumi up + kubeadm join) |
| `remote/` | SSH 기반 노드 접근 + diagnosis |
| `registration/` | provisioning 완료된 cluster 를 `domain/cluster/` 로 승격 (kubeconfig 발급 + agent ConfigMap apply) |
| `admin/` | 운영자 도구 — drift detect, state machine 강제 전환 |
| `payload/` (구 `support/`) | RabbitMQ 메시지 payload 생성 / 직렬화 helper |
| `preflight/` (구 `query/`) | 생성 전 검증 — provider 정규화, credential, VM options, cost preview |

## 클래스 prefix 규칙

폴더는 `provisioning/` 이지만 클래스 prefix 는 의도적으로 `VmCluster*` 유지:
- 폴더 = **도메인 boundary** ("VM 인프라 프로비저닝 영역")
- 클래스 prefix = **자원 specific** ("VM 기반 K8s 클러스터의 entity / controller / workflow")
- DB table `vm_cluster` 과 1:1 — `VmClusterEntity` 이름이 자연스러움

향후 다른 프로비저닝 방식 (bare-metal / Crossplane / EKS 관리형) 추가 시:
- `BareMetalCluster*` 또는 `ManagedCluster*` 클래스가 같은 폴더에 공존 가능
- 폴더 이름 `provisioning/` 은 의미 불변

## REST path / DB table / config key 의 호환성

운영자 / frontend / docker-compose env override 영향을 피하기 위해 다음은 **변경되지 않음**:

| 항목 | 값 (그대로) |
|---|---|
| REST base path | `/api/v1/vm-clusters/*` |
| DB table 명 | `vm_cluster`, `vm_cluster_node`, `vm_cluster_log` 등 |
| Spring config key | `vm-cluster-workflow.*`, `VM_CLUSTER_WORKFLOW_*` env |
| RabbitMQ exchange / queue 이름 | `vm-cluster.workflow`, `vm-cluster.provision` 등 |
| Bruno 컬렉션 폴더 | `.bruno/VM Cluster (VM 클러스터)/` |

이 항목들은 외부 계약 — 변경 시 마이그레이션 비용 큼. 도메인 폴더 rename 의 ROI 와 별개.
