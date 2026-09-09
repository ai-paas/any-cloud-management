# 로컬에서 cluster-agent 붙이기 (k3d)

로컬 개발 환경에 실제 cluster-agent 를 연결하는 절차. K8s 관련 API 는 agent 세션이 없으면
전부 503 을 반환하므로, 붙이지 않으면 API 표면의 상당 부분을 로컬에서 검증할 수 없습니다.

실측으로 Bruno 컬렉션 88개 요청 중 agent 없이 31건, 붙인 뒤 45건이 성공했습니다.

## 왜 k3d 로 되는가

agent 는 backend 로 **outbound gRPC** 를 엽니다. backend 가 cluster 안으로 들어오는 구조가
아니라서 k3d 처럼 로컬 컨테이너 기반 K8s 면 충분합니다. VM 이나 별도 네트워크 설정이 필요 없습니다.

## 사전 조건

- `make dev-up` 으로 backend 가 떠 있을 것 (REST 8888, gRPC 9090)
- `k3d`, `helm`, `kubectl`

## 1. 전용 클러스터

기존 클러스터를 쓰면 kubeconfig 나 기존 워크로드와 얽히므로 따로 만듭니다.

```bash
k3d cluster create anycloud-e2e --agents 0
```

## 2. agent 이미지 준비

로컬에서 빌드해 k3d 로 넣습니다. `ghcr.io/aipaas/cluster-agent` 는 공개 이미지가 아닙니다.

```bash
docker build --build-arg CMD_TARGET=cluster-agent \
  -t cluster-agent:local apps/agent
k3d image import cluster-agent:local -c anycloud-e2e
```

## 3. cluster 등록 + 토큰 발급

```bash
curl -s -X POST http://localhost:8888/v1/clusters \
  -H 'content-type: application/json' \
  -H "Idempotency-Key: $(uuidgen)" \
  -d '{"source":"registered","clusterName":"k3d-e2e",
       "spec":{"clusterType":"Public","provider":"AWS","description":"local"}}'
```

응답 `data.bootstrap.token` 이 등록 토큰입니다. **유효기간 10분**이라 발급 후 바로 설치합니다.

## 4. agent 설치

```bash
GW=$(docker network inspect k3d-anycloud-e2e \
       --format '{{range .IPAM.Config}}{{.Gateway}}{{end}}')

helm --kube-context k3d-anycloud-e2e install cluster-agent \
  apps/agent/deploy/helm/cluster-agent \
  --namespace aipaas-system --create-namespace \
  --set bootstrap.registrationToken="$TOKEN" \
  --set backend.grpcAddr="$GW:9090" \
  --set image.repository=cluster-agent \
  --set image.tag=local \
  --set image.pullPolicy=IfNotPresent \
  --wait
```

**`backend.grpcAddr` 를 반드시 덮어써야 합니다.** 등록 응답의 `helmInstallCommand` 는 backend 의
`agent.grpc.public-endpoint` 값(기본 `127.0.0.1:9090`)을 넣어주는데, 그 주소는 파드 안에서
자기 자신을 가리켜 연결되지 않습니다. `host.k3d.internal` 도 파드 DNS 에서 해석되지 않아
Docker 네트워크 게이트웨이 IP 를 씁니다.

## 5. 확인

```bash
kubectl --context k3d-anycloud-e2e -n aipaas-system logs -l app.kubernetes.io/instance=cluster-agent
```

아래 두 줄이 나오면 연결된 것입니다.

```
"msg":"bootstrap: registered","cluster_id":"k3d-e2e","status":"CLUSTER_STATUS_ACTIVE"
"msg":"runtime stream open"
```

API 로도 확인합니다.

```bash
curl -s "http://localhost:8888/v1/clusters/k3d-e2e/namespaces/-/nodes?pageSize=5"
```

## 정리

```bash
k3d cluster delete anycloud-e2e
```

## 알려진 한계

| 증상 | 원인 |
|---|---|
| helm install/uninstall 이 403 | agent allowlist 정책. 의도된 동작 — [allowlist 가이드](./cluster-agent-allowlist-narrow.md) |
| `/metrics/query`, `/observability/*` 가 502 | 클러스터에 Prometheus / Alertmanager / Grafana 미설치 |
| `APPLY_AGENT_CONFIG` 가 `COMMAND_NOT_FOR_MODE` | agent 가 `mode=core` 로 떠 있음. 해당 명령은 features 모드 필요 |

## 대안: compose 네트워크 공유

k3d 노드를 backend 와 같은 Docker 네트워크에 두면 게이트웨이 IP 대신 서비스명을 쓸 수 있습니다.

```bash
k3d cluster create anycloud-e2e --agents 0 --network any-cloud-management_default
# --set backend.grpcAddr=anycloud-backend:9090
```

IP 가 환경마다 달라지는 문제를 피할 수 있으나, 네트워크 수명주기가 compose 에 묶입니다.
