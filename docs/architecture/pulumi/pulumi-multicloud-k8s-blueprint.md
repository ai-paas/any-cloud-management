# Pulumi 기반 멀티 CSP VM Kubernetes 프로비저닝 청사진

Spring Boot 백엔드에 `Pulumi Automation API` 계층을 붙여서 여러 CSP 에 대해 VM 기반 Kubernetes
클러스터를 자동 생성하는 구조입니다.

기준 구현은 다음과 같습니다.

- 백엔드는 Java 21 / Spring Boot 입니다.
- IaC 엔진은 Pulumi Go 입니다.
- 현재 실제 구현은 AWS 입니다.
- 확장 대상은 GCP, Azure, OCI, Alibaba Cloud, DigitalOcean, OpenStack 입니다.

## 1. 디렉터리 구조

```text
any-cloud-management/
├─ anycloud/                         # 기존 Spring Boot 백엔드
├─ infra/
│  └─ pulumi/
│     ├─ Pulumi.yaml
│     ├─ go.mod
│     ├─ main.go
│     └─ pkg/
│        ├─ config/
│        │  └─ spec.go
│        ├─ model/
│        │  └─ types.go
│        ├─ providers/
│        │  ├─ factory.go
│        │  ├─ provider.go
│        │  └─ aws/
│        │     └─ aws.go
│        └─ userdata/
│           └─ kubeadm.go
├─ manifests/
│  ├─ monitoring/
│  │  └─ gpu-observability.yaml
│  └─ platform/
│     └─ postgresql.yaml
└─ docs/
   ├─ README.md (색인)
   ├─ architecture/ (청사진·런타임·용어)
   ├─ api/         (VM Options·CSP Credential·Preflight)
   ├─ workflow/    (RabbitMQ·E2E 체크리스트)
   └─ operations/  (Day-2·DB 적용)
```

## 2. 상위 아키텍처

```text
User / API
   ↓
ClusterController
   ↓
ProvisioningService
   ↓
Pulumi Automation API
   ↓
infra/pulumi (Go program)
   ↓
Provider-specific resource creation
   ↓
VM-based kubeadm cluster
```

권장 역할 분리는 다음과 같습니다.

- Spring Boot 의 책임은 다음과 같습니다.
  - 사용자 요청 검증입니다.
  - 클러스터 메타데이터 저장입니다.
  - Pulumi stack 생성/업데이트/삭제 오케스트레이션입니다.
  - Output 을 `ClusterEntity` 로 매핑합니다.
- Pulumi Go Program 의 책임은 다음과 같습니다.
  - 네트워크/VPC/보안/IAM/VM/DB 생성입니다.
  - kubeadm user-data 생성입니다.
  - 접속 정보와 운영 정보 export 입니다.

## 3. Provider 추상화 전략

Provider 별 구현체는 다음 계약을 따릅니다.

```go
type ClusterProvisioner interface {
    Provision(ctx *pulumi.Context, spec *model.ClusterSpec) (pulumi.Map, error)
}
```

공통으로 맞춰야 할 계약은 다음과 같습니다.

- 입력
  - provider
  - clusterName
  - region
  - kubernetesVersion
  - masterCount (default 1, odd-only — etcd quorum)
  - workerCount
  - sshUser
  - network cidr
  - instance size
  - db 사용 여부
- 출력
  - clusterName
  - provider
  - apiServerUrl
  - master / workers instance id
  - public/private ip
  - public dns
  - ssh command
  - kubeconfig fetch command
  - ssh private key pem(secret)
  - db endpoint(optional)

권장 구현 순서는 다음과 같습니다.

1. AWS 를 기준 구현으로 완성합니다.
2. GCP/Azure 는 네트워크/방화벽/VM output 계약을 동일화합니다.
3. OCI/Alibaba/DO/OpenStack 은 같은 인터페이스로 확장합니다.
4. Backend 는 provider 문자열만 바꿔도 같은 흐름으로 실행합니다.

## 4. CSP별 리소스 매핑

| 공통 개념 | AWS | GCP | Azure | OCI | Alibaba | DigitalOcean | OpenStack |
|---|---|---|---|---|---|---|---|
| Network | VPC | VPC Network | VNet | VCN | VPC | VPC | Network |
| Subnet | Subnet | Subnet | Subnet | Subnet | VSwitch | VPC Subnet | Subnet |
| Route | Route Table | Route | Route Table | Route Table | Route Table | Route | Router |
| Gateway | Internet Gateway | Cloud Router/NAT or default route | Public IP + NAT | Internet Gateway | Internet Gateway | Managed | External Network |
| Firewall | Security Group | Firewall Rule | NSG | Security List / NSG | Security Group | Firewall | Security Group |
| IAM | IAM Role/Profile | Service Account | Managed Identity / Role | Dynamic Group / Policy | RAM Role | Token/Project | Keystone Role |
| VM | EC2 | Compute Engine | VM | Compute Instance | ECS | Droplet | Nova Server |
| Public IP | Auto-assign/EIP | External IP | Public IP | Reserved Public IP | EIP | Floating IP | Floating IP |

## 5. kubeadm 자동화 원칙

현재 샘플은 과제/PoC 속도를 우선한 구조입니다.

- Master 1대 + Worker N대 입니다.
- UserData(cloud-init shell) 로 kubeadm 자동 설치합니다.
- Worker 는 `--discovery-token-unsafe-skip-ca-verification` 을 사용합니다.
  - PoC 에서는 편하지만 운영에서는 권장하지 않습니다.
- CNI 는 Calico 기준입니다.
- Ingress 는 ingress-nginx 기준입니다.
- GPU Operator 는 Helm 기준입니다.

운영 전환 시 바꿔야 할 부분은 다음과 같습니다.

- join token 과 CA hash 를 안전하게 분배합니다.
- SSH key 를 Pulumi state 대신 외부 secret manager 로 이전합니다.
- DB 비밀번호를 Pulumi config secret 또는 Vault 로 관리합니다.
- Control plane 을 HA(3노드) 로 확장합니다.

## 6. DB 설계

메타데이터 저장 DB 는 2가지 경로가 있습니다.

- 권장: CSP Managed PostgreSQL 입니다.
  - AWS RDS / GCP Cloud SQL / Azure Database for PostgreSQL 입니다.
  - 장애 복구와 백업이 쉽습니다.
- 대안: K8s 내부 PostgreSQL 입니다.
  - PoC/사내망/오프라인 환경에서 유용합니다.
  - 이 리포지토리에는 예시 manifest 가 포함되어 있습니다.

현재 AWS 샘플은 `db.enabled=true` 일 때 RDS PostgreSQL 을 같이 만듭니다.

## 7. Java Backend 연동 포인트

백엔드에서는 Pulumi Automation API 를 사용해 stack 을 생성하고,
결과 output 을 기존 `ClusterEntity` 에 매핑하면 됩니다.

권장 서비스 흐름은 다음과 같습니다.

1. 사용자 요청 수신입니다.
2. DB 에 `PROVISIONING` 상태 저장입니다.
3. stack name 생성입니다.
4. Pulumi config set 입니다.
5. `up()` 실행입니다.
6. outputs 수집입니다.
7. `ClusterEntity` 갱신입니다.
8. 상태를 `READY` 또는 `FAILED` 로 반영합니다.

권장 output 매핑은 다음과 같습니다.

- `apiServerUrl` -> `ClusterEntity.apiServerUrl`
- `masterPrivateIp` -> `ClusterEntity.apiServerIp`
- `provider` -> `ClusterEntity.clusterProvider`
- `clusterType` -> `vm-kubeadm`
- `kubeconfigFetchCommand`, `sshPrivateKeyPem`, `nodes` -> 별도 provisioning metadata 테이블 권장

## 8. 운영/보안/장애 관점

### 보안

- SSH ingress 는 사무실 IP/VPN 대역으로 제한합니다.
- 6443 API Server 포트는 관리자 CIDR 만 허용합니다.
- Pulumi state backend 는 S3 + KMS + DynamoDB lock 또는 Pulumi Cloud 를 사용합니다.
- kubeconfig 와 private key 는 DB 평문 저장을 금지합니다.
- GPU 노드 분리 시 taint/label 을 강제합니다.

### 장애 대응

- 클러스터 생성은 비동기 Job 으로 실행합니다.
- create/update/destroy 이력 테이블을 저장합니다.
- 각 Provider 별 retry 정책을 분리합니다.
- VM 생성 성공 후 kubeadm 실패 시 partial cleanup 모드를 제공합니다.

### 운영

- stack 단위 네이밍 규칙을 표준화합니다.
  - 예: `org/project/provider-env-cluster` 입니다.
- Output JSON 을 그대로 저장하지 말고 표준 스키마로 정규화합니다.
- CSP 별 quota 검사 단계를 선행합니다.

## 9. 최적화 방향

- VM 이미지에 kubeadm/containerd 를 baked image 로 선탑재합니다.
- cloud-init 길이가 길어지면 object storage script fetch 방식으로 전환합니다.
- GPU 노드는 별도 node pool 로 분리합니다.
- bootstrap 이후에는 GitOps(Argo CD/Flux) 로 Day-2 운영을 분리합니다.
- 모니터링 스택은 Helm values 로 통합 관리합니다.

## 10. 실행 예시

Pulumi stack config 예시는 다음과 같습니다.

```yaml
config:
  anycloud-k8s:provider: aws
  anycloud-k8s:name: demo-aws
  anycloud-k8s:environment: poc
  anycloud-k8s:region: ap-northeast-2
  anycloud-k8s:vpcCidr: 10.42.0.0/16
  anycloud-k8s:subnetCidrs:
    - 10.42.1.0/24
    - 10.42.2.0/24
  anycloud-k8s:masterInstanceType: t3.large
  anycloud-k8s:workerInstanceType: t3.large
  anycloud-k8s:masterCount: 1        # HA: 3/5/7 (odd-only). 1 이면 single master (legacy).
  anycloud-k8s:workerCount: 2
  anycloud-k8s:sshUser: ubuntu
  anycloud-k8s:kubernetesVersion: 1.31
  anycloud-k8s:podCidr: 192.168.0.0/16
  anycloud-k8s:joinToken: abcdef.0123456789abcdef
  anycloud-k8s:dbEnabled: true
  anycloud-k8s:dbName: anycloud
  anycloud-k8s:dbUsername: anycloud
  anycloud-k8s:dbPassword:
    secure: BASE64_ENCODED_SECRET
```

실행 순서는 다음과 같습니다.

```bash
cd infra/pulumi
pulumi stack init dev
pulumi config set anycloud-k8s:provider aws
pulumi config set anycloud-k8s:name demo-aws
pulumi config set aws:region ap-northeast-2
pulumi config set --path anycloud-k8s:subnetCidrs[0] 10.42.1.0/24
pulumi config set --path anycloud-k8s:subnetCidrs[1] 10.42.2.0/24
pulumi config set --secret anycloud-k8s:dbPassword 'change-me'
pulumi up
pulumi stack output
```

## 11. 확장 우선순위

실제 과제 제출 관점에서는 아래 순서를 추천합니다.

1. AWS 완성입니다.
2. OpenStack 완성입니다.
3. GCP 또는 Azure 1개 추가입니다.
4. 나머지 CSP 는 동일 인터페이스와 매핑 표로 확장합니다.

이렇게 하면 "멀티 CSP 구조 설계 + 2~3개 실제 구현 + 7개 확장 가능성" 을 가장 설득력 있게 보여줄 수 있습니다.
