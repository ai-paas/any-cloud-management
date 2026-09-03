# Day-2 Operations

VM 기반 클러스터를 *생성*한 후 *지속 운영*하기 위한 10개 시나리오입니다. 각 항목은 다음 형식을 따릅니다.

- **자동화 범위** — 현재 코드가 처리 / 수동 절차
- **단계** — 실제 운영자가 따라하는 순서
- **검증** — 끝났음을 확인하는 신호
- **주의** — 흔한 실패 모드

전제: 본 백엔드는 Spring Boot + Pulumi + Bootstrap Worker + RabbitMQ 구성입니다.
state backend 는 RustFS (S3 호환), secrets 는 OpenBao (Vault 호환) 입니다.

---

## 1. 노드 Scale Up / Down

워커 노드 수를 변경합니다 (예: 3 → 5 또는 5 → 3).

**자동화 범위**
- 자동: Pulumi config (`workerCount`) 갱신 → `pulumi up` 으로 VM 추가/삭제 생성, 삭제
- 자동: 신규 VM 의 cloud-init 으로 kubeadm join 자동 수행
- 수동: cluster-autoscaler 도입은 별건 (CSP cloud-provider 필요)

**단계 (Scale Up)**
1. `PATCH /v1/clusters/{name}` body `{spec:{workerCount: N+1}}` 로 워커 수 증가 → 202 + Operation (`SCALE_CLUSTER`)
2. 백엔드가 Pulumi config 갱신 후 `pulumi up` 실행
3. 신규 VM 부팅 시 cloud-init 안의 kubeadm join 자동 수행
4. `kubectl get nodes` 로 새 노드가 Ready 상태인지 확인 (또는 `GET /v1/operations/{id}/events` SSE)

**단계 (Scale Down)**
1. 줄일 노드를 식별합니다 (선호: 가장 최근에 생성된 노드 또는 워크로드가 적은 노드).
2. 운영자가 수동으로 `kubectl cordon <node>` + `kubectl drain <node> --ignore-daemonsets --delete-emptydir-data` 를 실행합니다.
3. `workerCount` 를 감소시켜 `PATCH /v1/clusters/{name}` body `{spec:{workerCount: N-1}}` 호출합니다.
4. Pulumi 가 해당 VM 인스턴스를 삭제합니다 (Pulumi 의 ordering 은 보통 created order 기준이며, CSP 별 차이가 있습니다).
5. `kubectl delete node <name>` 로 etcd 에서 제거합니다.

**검증**
- `kubectl get nodes` 노드 수 일치
- workload 가 남은 노드로 재스케줄됨 (`kubectl get pods -A -o wide` 로 확인)
- VM 클러스터 상태 = `READY`, `currentWorkflowStep` 정상

**주의**
- Pulumi 가 어느 인스턴스를 삭제할지는 resource name 기반입니다 (suffix index 등). 의도된 노드와 다를 수 있습니다. CSP 별로 다르며, **PoC 단계에선 운영자가 미리 drain 한 후 scale 호출** 하는 것이 안전합니다.
- scale-down 중에 새 워크로드가 들어오면 drain 이 실패합니다. taint 또는 PDB 를 점검해야 합니다.
- master 수 변경은 본 워크플로우 범위 밖입니다 (현재 master=1 고정, control-plane HA 는 별건입니다).

---

## 2. Kubernetes Minor Version Upgrade

클러스터를 K8s X.Y.Z → X.(Y+1).Z 로 무중단에 가깝게 업그레이드합니다.

**자동화 범위**
- 수동: 현재 자동화 없음

**단계 (one-time, 운영자 수동)**
1. control-plane 1대를 먼저
   - `kubeadm upgrade plan` 으로 호환성 확인
   - `apt install kubeadm=<X.Y.Z>` 후 `kubeadm upgrade apply <X.Y.Z>`
   - `apt install kubelet=<X.Y.Z> kubectl=<X.Y.Z>` → `systemctl restart kubelet`
2. (control-plane HA 인 경우 나머지 control-plane 도 `kubeadm upgrade node`)
3. 워커 한 대씩
   - `kubectl drain <node>`
   - `apt install kubeadm/kubelet/kubectl=<X.Y.Z>`
   - `kubeadm upgrade node` → `systemctl restart kubelet` → `kubectl uncordon`
4. addon 호환성 확인 (CNI/Ingress/GPU Operator). 필요 시 동시 업그레이드 (§4)
5. Pulumi `kubernetesVersion` config 갱신 (다음 신규 노드가 새 버전으로 생성되도록)

**검증**
- `kubectl version` 모든 노드 일치
- `kubectl get nodes -o wide` STATUS=Ready, VERSION 모두 새 버전
- 핵심 워크로드 정상 (Pod ready, Ingress 응답, GPU Pod 동작)

**주의**
- minor 두 단계 점프 금지입니다 (kubeadm 정책). 항상 X.Y → X.(Y+1) 입니다.
- N-2 deprecation 매트릭스를 확인해야 합니다 (특히 CRI/CNI 호환성).
- master 1대 환경에서는 control-plane upgrade 중 짧은 downtime (1-2분) 이 발생합니다.
- ETCD 백업을 권장합니다 (`etcdctl snapshot save` 후 진행).

---

## 3. OS 패치 & 재부팅

정기적인 OS 보안 패치 + 재부팅을 워크로드 영향 최소화로 수행합니다.

**자동화 범위**
- 수동: 운영자가 노드별로 drain → patch → reboot → uncordon

**단계 (per node)**
1. `kubectl cordon <node>`
2. `kubectl drain <node> --ignore-daemonsets --delete-emptydir-data`
3. SSH 접속 → `apt update && apt upgrade -y && unattended-upgrade`
4. `reboot`
5. 재부팅 후 `kubelet` 자동 시작 확인 → `kubectl uncordon <node>`

**검증**
- `uname -a` kernel 버전 갱신
- `kubectl get nodes` STATUS=Ready
- Pod 재스케줄 후 정상 동작

**주의**
- DaemonSet (Calico, Ingress, GPU driver) 는 drain 영향을 받지 않습니다. `--ignore-daemonsets` 가 필수입니다.
- PVC 사용하는 Pod 는 emptyDir 만 삭제됩니다. PV 데이터는 보존됩니다.
- 여러 노드 동시 재부팅은 금지입니다. PDB (PodDisruptionBudget) 으로 보장해야 합니다.
- master 재부팅 시 짧은 control-plane downtime 이 발생합니다 (단일 master 가정).

---

## 4. CNI / Ingress / GPU Operator 업그레이드

Helm 으로 설치된 cluster add-on 의 마이너 업그레이드입니다.

**자동화 범위**
- 자동: 본 백엔드의 `/v1/clusters/{c}/helm-releases` API + Helm CLI 가 처리합니다.
- 수동: 호환성 매트릭스 사전 점검, 롤백 결정

**단계**
1. 호환성 매트릭스 확인
   - K8s 버전 vs CNI 버전 (Calico 호환 매트릭스 등)
   - K8s 버전 vs Ingress-nginx
   - GPU Operator vs NVIDIA driver
2. `helm upgrade` 또는 본 백엔드 `POST /v1/clusters/{c}/helm-releases` (JSON + values 객체 또는 multipart valuesFile) 호출 → 202 + Operation
3. 단계적 적용: dev → staging → prod
4. 새 values.yaml 변경 사항을 PR 로 보존 (GitOps 패턴, 별건)

**롤백 / 제거**
- 롤백: `POST /v1/clusters/{c}/helm-releases/{r}/operations` body `{type:"rollback", revision: N, wait: true}` → 200 + Operation
- 제거: `DELETE /v1/clusters/{c}/helm-releases/{r}?keepHistory=false&wait=true` → 202 + Operation (UNINSTALL_HELM_RELEASE)

**검증**
- `helm status <release>` STATUS=deployed
- 해당 워크로드 정상 (CNI: 노드 간 통신, Ingress: 외부 요청 200, GPU: `nvidia-smi` in Pod)

**주의**
- CNI 업그레이드 중 Pod 네트워크가 일시 끊길 수 있습니다.
- Ingress-nginx 의 controller pod 는 PDB 적용을 권장합니다.
- 롤백: `helm rollback <release> <rev>` 입니다. 다만 CRD 변경이 있으면 자동 롤백 불가 (수동 정리) 입니다.

---

## 5. 클러스터 자격증명 / CA 회전

kubeadm 이 발급한 인증서를 만료 전에 갱신합니다.

**자동화 범위**
- 수동: 현재 자동화 없음

**단계**
1. 만료 확인: `kubeadm certs check-expiration` (control-plane 에서)
2. 갱신: `kubeadm certs renew all`
3. control-plane 컴포넌트 재시작 (`docker restart` 또는 정적 Pod 자동)
4. kubeconfig 재발급: `kubeadm kubeconfig user --client-name <admin> --org system:masters`
5. **cluster-agent 재시작** — 새 kubeconfig 의 SA token 이 agent pod 의 init script 가 자동 fetch (in-cluster mount). agent 가 새 자격으로 backend 와 gRPC 재연결.
6. KubernetesClient cache 자동 invalidate (이미 구현됨)

**검증**
- `kubeadm certs check-expiration` 모든 cert 1y+ 만료
- `kubectl get nodes` 정상 (기존 admin.conf 무효, 새 kubeconfig 로 접근 가능)
- 본 백엔드의 `POST /v1/clusters/{name}/connectivity-checks` 통과 (connected=true)

**주의**
- kubeadm 의 기본 CA 만료는 10년입니다. client cert 는 1년입니다. cert 갱신을 안 하면 1년 후 control-plane 통신이 끊깁니다.
- 갱신 전에 ETCD snapshot 백업이 필요합니다.
- kubelet 의 client cert 는 `--rotate-certificates=true` (kubeadm 기본) 로 자동 회전됩니다 → 수동 갱신 대상은 control-plane 컴포넌트만 입니다.

---

## 6. Pulumi state 백업 & 복구 (RustFS)

state backend 손상, 삭제 시 복구할 수 있어야 합니다.

**자동화 범위**
- 수동: cron 으로 `mc mirror` 또는 RustFS replication

**단계 (정상 운영 시 백업)**
1. 별도 RustFS 클러스터(off-site) 또는 외부 S3 호환 storage 준비
2. cron: `mc mirror --overwrite rustfs/pulumi-state backup/pulumi-state-$(date +%Y%m%d)`
3. 최근 N 일치 보관 정책 (`mc rm --recursive --older-than`)

**단계 (state 손상 복구)**
1. 손상된 stack 확인: `pulumi stack ls`, `pulumi stack export --stack <name>` 실패 시
2. 백업 시점의 state 객체를 RustFS bucket 으로 복원: `mc cp backup/.../<stack> rustfs/pulumi-state/...`
3. `pulumi stack import --file <exported>.json` 또는 `pulumi refresh` 로 클라우드 실제 상태와 동기화
4. drift detection 후 `pulumi up` 으로 일치 (이 단계가 가장 위험, 운영자 직접 검토)

**검증**
- `pulumi stack output` 정상 반환
- `pulumi preview` 시 No changes (cloud 실제 상태와 state 일치)

**주의**
- state 가 손상되면 자원이 Pulumi 로부터 *해방* 됩니다. cloud 콘솔에서 수동 정리하거나 import 로 다시 종속시켜야 합니다.
- `pulumi refresh` 는 cloud → state 방향 동기화입니다. cloud 자원이 변경됐을 때 위험할 수 있습니다.
- ETCD/DB 와 달리 Pulumi state 는 단순 JSON 이라 file-level 백업으로 충분합니다.

---

## 7. OpenBao Transit 키 회전

무중단으로 secrets 암호화 키를 교체합니다.

**자동화 범위**
- 수동: OpenBao CLI 명령

**단계**
1. 키 회전: `bao write -f transit/keys/anycloud-pulumi/rotate` → 새 버전(v2) 생성
2. 자동 rewrap: Pulumi state 내부의 secret blob 은 새 버전을 자동 사용하지 않습니다. 명시적 rewrap 이 필요합니다.
3. 각 stack 에 대해 `pulumi stack change-secrets-provider hashivault://openbao:8200/anycloud-pulumi` 호출 (재암호화 트리거)
4. 최소 키 버전 설정: `bao write transit/keys/anycloud-pulumi/config min_decryption_version=2` (구버전 해제)

**검증**
- `bao read transit/keys/anycloud-pulumi` 의 `latest_version=2`
- 모든 stack 의 `pulumi stack output --show-secrets` 정상 복호화

**주의**
- `min_decryption_version` 을 올리기 전 모든 stack 의 rewrap 완료를 확인해야 합니다. 안 그러면 옛 secret 복호화에 실패합니다.
- 키 자체는 OpenBao Raft storage 에 영속됩니다. backup-restore 정책은 별도입니다 (§Vault docs).

---

## 8. 장애 시 Rollback / Re-Provision

Pulumi up / Bootstrap 실패 시 깨끗하게 정리하고 재시도합니다.

**자동화 범위**
- 부분 자동: `workflowSupportService.failWithDiagnostics` 가 entity 상태와 진단 로그 보존
- 자동: `vm_cluster.bootstrap_log` 에 cloud-init/kubelet/kubectl 출력 일부 저장
- 자동: `POST /v1/clusters/{name}/operations` body `{type:"retryWorkflow"}` → 202 + Operation
- 수동: 운영자의 재시도 / 강제 destroy 결정

**단계 (Bootstrap 실패 후 재시도)**
1. 상태 확인: `GET /v1/clusters/{name}` 의 `provisioningStatus=FAILED`, `lastFailedStep`, `bootstrapLog` 검사
2. 원인 분석 — `bootstrapLog` 의 `cloud-init-output.log`, `kubelet journal` 확인
3. 인프라는 살아있음 (Pulumi up 성공) → bootstrap 만 재시도 필요
4. 운영자가 수동으로 SSH 접속해 부분 정리 (`kubeadm reset`, 데몬 재시작 등)
5. workflow 재시작: `POST /v1/clusters/{name}/operations` body `{type:"retryWorkflow"}`, 또는 RabbitMQ 로 메시지 재발행

**단계 (Pulumi up 실패, partial resources)**
1. `pulumi stack` 출력 확인 — 어떤 자원이 생성됐는지
2. `pulumi destroy` 시도 (Pulumi 가 알고 있는 자원은 정리됨)
3. 수동 잔여물 정리 — cloud 콘솔에서 orphan 자원 (예: 일부 VM 만 생성됨, EIP 가 detach 되지 않음 등)
4. `DELETE /v1/clusters/{name}` 로 백엔드 entity 도 삭제 후 재요청

**검증**
- 재시도 후 `provisioningStatus=READY`
- `workflow_message_log` 에 PROCESSED 로 마무리되는 메시지 확인
- 실제 K8s 클러스터 healthy

**주의**
- 무한 재시도 방지: `workflow_retry_count` (이미 컬럼 있음) 임계 도달 시 manual intervention 필요로 분류됩니다.
- 부분 인프라가 남으면 비용이 발생합니다. 14일 이내 정리 정책을 권장합니다.
- DLQ 적재 메시지는 운영자 검토 후 재발행합니다 (§9).

---

## 9. 워크플로우 메시지 재처리 (DLQ)

dead-letter queue 적재 메시지를 검토 후 재처리하거나 폐기합니다.

**자동화 범위**
- 자동: RabbitMQ 의 DLX 라우팅 (이미 `vm-cluster.workflow.dlx` 구성됨)
- 자동: `workflow_message_log` 에 FAILED 결과 영속 (이번 commit 으로 확보)
- 수동: 운영자가 DLQ 메시지 검토 + 재발행 결정

**단계**
1. DLQ 상태: `GET /v1/workflow/queues` 응답에서 `vm-cluster.workflow.dlq` 의 메시지 수 확인
2. RabbitMQ 관리 UI (`http://localhost:15672`) 에서 DLQ 메시지 payload 확인 → `messageId` 추출
3. `workflow_message_log` 에서 동일 messageId 의 처리 이력 조회
   ```sql
   SELECT step, result, error_message FROM aipaas.workflow_message_log
    WHERE message_id = '...';
   ```
4. 결정
   - **재처리**: 메시지를 원래 큐로 다시 publish (RabbitMQ UI 의 "Move messages") 또는 새 `messageId` 부여 후 백엔드 API 로 재요청
   - **폐기**: DLQ 에서 ack/purge

**검증**
- DLQ 비워짐 (또는 의도된 보존)
- 재처리한 메시지가 `workflow_message_log` 에 새 row 로 기록됨

**주의**
- 같은 `messageId` 를 그대로 재발행하면 `WorkflowMessageGuard` 가 SKIPPED_DUPLICATE 로 차단합니다. **재처리 시 새 messageId 부여가 필수입니다.**
- DLQ 적재 원인 분석 없이 재처리는 금지입니다 (root cause 미해결 시 즉시 다시 DLQ 행).
- 일정 기간(예: 30일) 보존 후 archive 정책을 권장합니다.

---

## 10. 모니터링 / 알람 SLO

백엔드와 클러스터 군의 운영 상태를 측정, 알람합니다.

**자동화 범위**
- 자동: kube-prometheus-stack 의 cluster 별 설치 — `cluster_addon` 테이블 + RabbitMQ async workflow.
  `POST /v1/clusters/{c}/addons` body `{type:"MONITORING", ...}` 로 install 합니다. 자세한 사용법은
  [`../operations/monitoring-usage.md`](../operations/monitoring-usage.md) 를 참고합니다.
- 자동: 신규 raw 쿼리 endpoint — `GET /v1/clusters/{c}/metrics/query?query=...` /
  `metrics/query_range`. Backend 가 agent → cluster 의 prometheus service 로 passthrough 합니다.
- 자동: Spring Actuator `/actuator/health`, `/actuator/metrics`
- 수동: 알람 규칙 / Grafana 대시보드 정의 (별건)

**핵심 SLI 후보**

| 영역 | SLI | 측정 |
|---|---|---|
| API 가용성 | `/v1/clusters` 5xx 비율 | Spring Actuator + Prometheus exporter |
| Provisioning 성공률 | `workflow_message_log` PROCESSED / (PROCESSED + FAILED) | DB 쿼리 export |
| Provisioning 소요 시간 | step 별 p50/p95/p99 `duration_ms` | DB 쿼리 export |
| 클러스터 가용성 | `kube_node_status_condition{condition="Ready"}=1` 비율 | 기존 Thanos 카탈로그 |
| GPU 가용성 | `DCGM_FI_DEV_XID_ERRORS` 증가율 | 기존 Thanos 카탈로그 |
| RabbitMQ DLQ 적재 | DLQ depth > 0 (즉시 알람) | RabbitMQ Prometheus exporter |
| RustFS 가용성 | bucket put/get 성공률 | RustFS exporter |
| OpenBao 가용성 | transit decrypt 성공률 | OpenBao audit log |

**권장 SLO 예시**
- API: 99.5% 5xx 미만 (월 단위)
- Provisioning 성공률: 95%+ (재시도 포함 후)
- 클러스터 Ready 비율: 99% (5분 평균)

**단계 (도입)**
1. DB 기반 SLI 를 Prometheus 로 export — `workflow_message_log` 쿼리를 주기 실행하는 작은 exporter 또는 백엔드 actuator metric 노출 (별건 작업) 합니다.
2. Grafana 대시보드 구성: 클러스터별 / step 별 시계열
3. Alertmanager 규칙: DLQ depth, FAILED 비율 spike, 클러스터 Ready 하락
4. on-call rotation + runbook (각 알람마다 본 문서의 해당 §로 링크)

**주의**
- 본 백엔드 자체는 SLO 측정 대상입니다. 백엔드가 죽으면 측정이 안 됩니다 → 외부 헬스체크 (uptime monitoring) 가 별도로 필요합니다.
- SLO 는 비용과 trade-off 입니다. PoC 단계에서는 "사실 보고" 수준으로 시작하고, 운영 정착 후 enforce 합니다.

