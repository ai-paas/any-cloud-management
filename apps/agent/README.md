# ClusterAgent

In-cluster agent for any-cloud-management. Backend ↔ Agent reverse gRPC.

> **외부 프로젝트에서 재사용** 하려면 → [`docs/external-deployment.md`](docs/external-deployment.md)
> (standalone / hybrid / backend-SSOT 3가지 모드, mTLS, 보안 체크리스트 포함)

## 위치

- Source: `apps/agent/`
- Proto (canonical, shared with backend starter): `libs/cluster-agent-spring-boot-starter/src/main/proto/agent/v1/`
- Go gen: `apps/agent/internal/gen/agent/v1/` (`buf generate` 에서 repo root 의 `buf.yaml` 워크스페이스가 위 proto 경로를 가리킴)
- Helm chart: `apps/agent/deploy/helm/`
- Architecture: `docs/architecture/cluster-agent.md`

## 빌드

```bash
# Local
go build -o bin/agent ./cmd/cluster-agent
./bin/agent

# Container
docker build -t aipaas/cluster-agent:dev .
```

## 환경 변수

| Key | Default | 설명 |
|---|---|---|
| `AGENT_MODE` | `core` | `core` / `installer` |
| `BACKEND_GRPC_ADDR` | `localhost:50050` | Backend gRPC endpoint |
| `CLUSTER_ID` | `(unset)` | Backend 가 발급 후 Secret 으로 주입 |
| `REGISTRATION_TOKEN` | `(unset)` | 1회용 JWT — bootstrap 시 사용 |

## Status

- [x] Skeleton main + signal handler
- [x] Dockerfile (distroless)
- [ ] gRPC client (Register RPC)
- [ ] Runtime stream
- [ ] Helm SDK handler
- [ ] mTLS cert rotation
- [ ] HA / leader election
