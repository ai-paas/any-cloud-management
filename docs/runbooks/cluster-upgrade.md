# Cluster upgrade — 운영 가이드

K8s minor version upgrade 는 **anycloud 의 책임 범위 밖**입니다. backup-agent / cluster-agent 는
upgrade 명령을 수행하지 않으며, 별도 manual 또는 community 도구로 처리합니다.

## 책임 분리

| 영역 | 담당 |
|---|---|
| 초기 VM provisioning + cloud-init (`apt-get install kubelet kubeadm kubectl`) | anycloud (Pulumi userdata) |
| 초기 `kubeadm init` / `kubeadm join` | anycloud backend (SSH bootstrap) |
| etcd snapshot / PKI 백업 | backup-agent |
| **K8s minor version upgrade** | **운영팀 수동 또는 외부 도구** |
| OS package upgrade (apt-get upgrade) | **운영팀 수동 또는 외부 도구** |
| node OS reboot | **운영팀 수동 또는 [kured](https://github.com/kubereboot/kured) 등** |

## upgrade 절차 (manual)

1. **사전 backup** — anycloud UI 의 cluster detail → "backup" 버튼 또는 backend admin API:
   ```
   POST /v1/admin/clusters/{clusterId}/backups/etcd
   POST /v1/admin/clusters/{clusterId}/backups/pki
   ```
   결과는 anycloud 의 backup storage 에 영속.

2. **control-plane 노드부터 upgrade** — kubeadm 표준 절차. lead master 부터:
   ```bash
   ssh <lead-master>
   sudo apt-mark unhold kubeadm
   sudo apt-get update && sudo apt-get install -y kubeadm=<new-version>
   sudo apt-mark hold kubeadm
   sudo kubeadm upgrade plan
   sudo kubeadm upgrade apply v<new-version>
   sudo apt-mark unhold kubelet kubectl
   sudo apt-get install -y kubelet=<new-version> kubectl=<new-version>
   sudo apt-mark hold kubelet kubectl
   sudo systemctl daemon-reload
   sudo systemctl restart kubelet
   ```
   이후 다른 master 들: `sudo kubeadm upgrade node` 수행.

3. **worker 노드 upgrade** — drain → upgrade → uncordon.
   ```bash
   # anycloud master 에서
   kubectl drain <worker> --ignore-daemonsets

   ssh <worker>
   sudo apt-mark unhold kubeadm
   sudo apt-get install -y kubeadm=<new-version>
   sudo kubeadm upgrade node
   sudo apt-mark unhold kubelet kubectl
   sudo apt-get install -y kubelet=<new-version> kubectl=<new-version>
   sudo apt-mark hold kubelet kubectl kubeadm
   sudo systemctl restart kubelet

   # back to master
   kubectl uncordon <worker>
   ```

4. **anycloud cluster 정보 갱신** — backend 가 다음 health check 주기에 자동 인식.
   강제 trigger: `POST /v1/admin/clusters/{clusterId}/refresh-status`.

## 외부 도구 권장

- **[system-upgrade-controller](https://github.com/rancher/system-upgrade-controller)** — Rancher 의
  K8s upgrade controller. Plan CR 으로 선언적 운영.
- **[kured](https://github.com/kubereboot/kured)** — OS package 적용 후 node reboot 자동화.
- **클라우드 매니지드 K8s** (EKS/GKE/AKS) 사용 시 — 해당 매니지드의 upgrade API 사용.

anycloud 가 이를 통합하지 않는 이유:
- upgrade 빈도 낮음 (cluster 당 연 1-2회) — 자동화 ROI 작음
- backup-agent 가 항상 host root 권한 보유해야 함 → 보안 부담 큼
- 위 외부 도구가 이미 검증된 path 제공
