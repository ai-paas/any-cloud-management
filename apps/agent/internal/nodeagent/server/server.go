// Package server — node-agent 의 gRPC 서버 구현.
package server

import (
	"context"
	"fmt"
	"os"
	"os/exec"
	"sync"

	nav1 "anycloud/agent/internal/gen/nodeagent/v1"
	"anycloud/agent/internal/nodeagent/backup"
	"google.golang.org/grpc"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"
)

// Service — Health + BackupEtcd / BackupPki 구현.
type Service struct {
	nav1.UnimplementedNodeAgentServer

	NodeName string

	// 한 노드에서 동시 backup RPC 차단.
	mu sync.Mutex
}

// New — production wiring. NodeName 은 K8s downward API 로 주입합니다 (env NODE_NAME).
func New() *Service {
	name := os.Getenv("NODE_NAME")
	if name == "" {
		hn, _ := os.Hostname()
		name = hn
	}
	return &Service{
		NodeName: name,
	}
}

// Register — gRPC server 에 본 service 를 등록합니다.
func (s *Service) Register(grpcServer *grpc.Server) {
	nav1.RegisterNodeAgentServer(grpcServer, s)
}

func (s *Service) Health(ctx context.Context, _ *nav1.HealthRequest) (*nav1.HealthResponse, error) {
	ver := detectKubeletVersion(ctx)
	_, kubeadmErr := exec.LookPath("kubeadm")
	return &nav1.HealthResponse{
		NodeName:         s.NodeName,
		KubeletVersion:   ver,
		KubeadmAvailable: kubeadmErr == nil,
	}, nil
}

// detectKubeletVersion — kubelet --version 출력의 두 번째 토큰. 실패 시 빈 문자열.
func detectKubeletVersion(ctx context.Context) string {
	out, err := exec.CommandContext(ctx, "kubelet", "--version").Output()
	if err != nil {
		return ""
	}
	parts := splitFields(string(out))
	if len(parts) < 2 {
		return ""
	}
	return parts[1]
}

func splitFields(s string) []string {
	out := make([]string, 0, 4)
	cur := make([]byte, 0, 32)
	flush := func() {
		if len(cur) > 0 {
			out = append(out, string(cur))
			cur = cur[:0]
		}
	}
	for i := 0; i < len(s); i++ {
		c := s[i]
		if c == ' ' || c == '\t' || c == '\n' || c == '\r' {
			flush()
		} else {
			cur = append(cur, c)
		}
	}
	flush()
	return out
}

// BackupEtcd — server-streaming. etcdctl snapshot save → chunk stream.
// 본 노드가 control-plane 이 아니면 PERMISSION_DENIED. mu.TryLock 으로 중복 backup 차단.
func (s *Service) BackupEtcd(req *nav1.BackupEtcdRequest, stream nav1.NodeAgent_BackupEtcdServer) error {
	if err := backup.EnsureControlPlane(); err != nil {
		return status.Errorf(codes.PermissionDenied, "this node is not a control-plane: %v", err)
	}
	if !s.mu.TryLock() {
		return status.Error(codes.Aborted, "another node-agent RPC is in progress")
	}
	defer s.mu.Unlock()

	opts := backup.EtcdBackupOptions{
		Endpoint:       req.GetEndpoint(),
		CaCertPath:     req.GetCaCertPath(),
		ClientCertPath: req.GetClientCertPath(),
		ClientKeyPath:  req.GetClientKeyPath(),
		ChunkSize:      int(req.GetChunkSize()),
	}

	sink := func(seq int, payload []byte, isLast bool, totalSha256 string, totalSize int64, metadata string) error {
		return stream.Send(&nav1.BackupChunk{
			Sequence:    int32(seq),
			Payload:     payload,
			IsLast:      isLast,
			TotalSha256: totalSha256,
			TotalSize:   totalSize,
			Metadata:    metadata,
		})
	}
	if err := backup.RunEtcdBackup(stream.Context(), opts, sink); err != nil {
		return status.Errorf(codes.Internal, "etcd backup failed: %v", err)
	}
	return nil
}

// BackupPki — server-streaming. /etc/kubernetes/pki → tar.gz → chunk stream.
// control-plane 검사 안 함 — 모든 노드에 PKI 일부가 있을 수 있음 (kubelet client cert 등).
// caller (cluster-agent) 가 의미 있는 노드 (master) 만 선택할 책임.
func (s *Service) BackupPki(req *nav1.BackupPkiRequest, stream nav1.NodeAgent_BackupPkiServer) error {
	if !s.mu.TryLock() {
		return status.Error(codes.Aborted, "another node-agent RPC is in progress")
	}
	defer s.mu.Unlock()

	opts := backup.PkiBackupOptions{
		IncludePaths: req.GetIncludePaths(),
		ChunkSize:    int(req.GetChunkSize()),
	}
	sink := func(seq int, payload []byte, isLast bool, totalSha256 string, totalSize int64, metadata string) error {
		return stream.Send(&nav1.BackupChunk{
			Sequence:    int32(seq),
			Payload:     payload,
			IsLast:      isLast,
			TotalSha256: totalSha256,
			TotalSize:   totalSize,
			Metadata:    metadata,
		})
	}
	if err := backup.RunPkiBackup(stream.Context(), opts, sink); err != nil {
		return status.Errorf(codes.Internal, "pki backup failed: %v", err)
	}
	return nil
}

// ListenAddress — DaemonSet 의 hostPort 또는 podIP 에 바인딩될 주소. NODE_AGENT_PORT 환경변수 또는 default.
func ListenAddress() string {
	port := os.Getenv("NODE_AGENT_PORT")
	if port == "" {
		port = "9090"
	}
	return fmt.Sprintf(":%s", port)
}
