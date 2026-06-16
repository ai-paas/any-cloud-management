// Command backup-agent — etcd / PKI 백업 전용 node-level binary.
//
// 각 K8s 노드에 DaemonSet (이름: backup-agent) 으로 배포되어 cluster-agent 의
// BACKUP_ETCD / BACKUP_PKI RPC 를 수행한다. etcdctl snapshot save + /etc/kubernetes/pki tar.gz
// 를 노드의 host filesystem 에서 직접 실행 — privileged + hostPath /etc/kubernetes/pki,
// /var/lib/etcd 등 필요 (deploy/k8s/backup-agent.yaml 참고).
//
// 이름 prefix `anycloud-` 는 Velero v1.10+ 의 `velero-node-agent` 와 K8s resource name 충돌 회피용.
// gRPC service 이름 `nodeagent.v1.NodeAgent` 는 wire compatibility 위해 유지.
//
// 본 binary 는 cluster-agent 와 같은 go module (apps/agent) 안에 있어 proto stubs / 로깅
// utility 를 공유한다.
package main

import (
	"context"
	"log/slog"
	"net"
	"os"
	"os/signal"
	"syscall"
	"time"

	"anycloud/agent/internal/nodeagent/server"
	"google.golang.org/grpc"
)

func main() {
	logger := slog.New(slog.NewJSONHandler(os.Stdout, nil))
	slog.SetDefault(logger)

	svc := server.New()
	addr := server.ListenAddress()
	listener, err := net.Listen("tcp", addr)
	if err != nil {
		slog.Error("listen failed", "addr", addr, "error", err)
		os.Exit(1)
	}

	grpcServer := grpc.NewServer()
	svc.Register(grpcServer)

	slog.Info("backup-agent starting",
			"node", svc.NodeName,
			"addr", addr)

	// graceful shutdown — SIGTERM 시 새 RPC 거부, 진행 중 RPC 는 timeout 까지 대기.
	sigCh := make(chan os.Signal, 1)
	signal.Notify(sigCh, syscall.SIGTERM, syscall.SIGINT)
	go func() {
		sig := <-sigCh
		slog.Info("shutdown signal received", "signal", sig)
		ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
		defer cancel()
		done := make(chan struct{})
		go func() {
			grpcServer.GracefulStop()
			close(done)
		}()
		select {
		case <-done:
			slog.Info("graceful stop complete")
		case <-ctx.Done():
			slog.Warn("graceful stop timed out — forcing")
			grpcServer.Stop()
		}
	}()

	if err := grpcServer.Serve(listener); err != nil {
		slog.Error("serve failed", "error", err)
		os.Exit(1)
	}
}
