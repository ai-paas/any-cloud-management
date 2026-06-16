// Package logstream — Pod log streaming runner.
//
// 흐름:
//  1. core/runtime.go 가 ControlMessage.OpenLogStream 수신 → goroutine 으로 Run() 호출.
//  2. 본 패키지가 AgentRuntime/StreamPodLogs bidi gRPC stream 을 backend 로 open.
//  3. 첫 LogPacket{Request, session_id} 송신 → backend 가 SSE bridge 와 매칭.
//  4. k8s.StreamPodLogs(ctx, opts) 로 io.ReadCloser 획득.
//  5. K8s stream → LogChunk packet 으로 backend 에 push (chunk size, flush 주기 제어).
//  6. K8s EOF / ctx cancel / backend 가 server-side complete 시 stream 종료.
package logstream

import (
	"context"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"time"

	"anycloud/agent/internal/config"
	agentv1 "anycloud/agent/internal/gen/agent/v1"
	"anycloud/agent/internal/k8s"
	"anycloud/agent/internal/tlsconfig"

	"google.golang.org/grpc"
	"google.golang.org/grpc/metadata"
)

// 각 LogChunk 의 max 크기 — 너무 작으면 RPC overhead, 너무 크면 backend memory.
// 64KB 면 K8s log line 보통 1-2개 + 여유. 사용자 burst (cat large file) 도 무난.
const maxChunkBytes = 64 * 1024

// SessionConfig — Run() 입력.
type SessionConfig struct {
	BackendAddr        string
	AgentIdentityToken string
	SessionID          string                      // backend 가 발급, OpenLogStream.session_id
	Request            *agentv1.LogStreamRequest   // backend 가 동봉
	// Transport TLS. zero value = plaintext.
	TLS tlsconfig.Config
}

// Runner — log streaming 세션 실행. K8s client + AllowList loader 보유.
type Runner struct {
	kube      k8s.Client
	allowlist *config.Loader
}

func New(kube k8s.Client, allowlist *config.Loader) *Runner {
	return &Runner{kube: kube, allowlist: allowlist}
}

// Run — context 종료 또는 stream 종료까지 블록. 호출은 별도 goroutine 권장.
//
// 에러 케이스:
//   - dial 실패 → 즉시 error 반환.
//   - K8s stream 실패 → backend 가 EOF 로 SSE 종료 (별도 에러 전달 채널 없음 — best-effort).
//   - AllowList 거부 → stream 열고 즉시 close (backend 가 empty SSE 로 인지).
func (r *Runner) Run(ctx context.Context, cfg SessionConfig) error {
	if cfg.Request == nil {
		return errors.New("logstream.Run: request required")
	}
	if cfg.BackendAddr == "" {
		return errors.New("logstream.Run: backend addr required")
	}
	if cfg.AgentIdentityToken == "" {
		return errors.New("logstream.Run: identity token required")
	}

	// AllowList 검증 — namespace 거부 시 즉시 종료 (stream 안 열고).
	// (backend 가 dispatcher 의 NAMESPACE_NOT_ALLOWED 응답과 달리 즉시 정보 못 받지만, 정책 일관성.)
	if r.allowlist != nil {
		policy := r.allowlist.Snapshot()
		if cfg.Request.Namespace != "" && !policy.IsNamespaceAllowed(cfg.Request.Namespace) {
			slog.Warn("logstream: namespace denied",
				slog.String("session_id", cfg.SessionID),
				slog.String("namespace", cfg.Request.Namespace))
			return errors.New("logstream: namespace not allowed: " + cfg.Request.Namespace)
		}
	}

	credsOpt, err := cfg.TLS.DialOption(cfg.BackendAddr)
	if err != nil {
		return fmt.Errorf("build TLS dial option: %w", err)
	}
	conn, err := grpc.NewClient(cfg.BackendAddr, credsOpt)
	if err != nil {
		return fmt.Errorf("dial backend for logstream: %w", err)
	}
	defer func() { _ = conn.Close() }()

	client := agentv1.NewAgentRuntimeClient(conn)
	md := metadata.Pairs("authorization", "Bearer "+cfg.AgentIdentityToken)
	streamCtx, cancel := context.WithCancel(metadata.NewOutgoingContext(ctx, md))
	defer cancel()

	stream, err := client.StreamPodLogs(streamCtx)
	if err != nil {
		return fmt.Errorf("open StreamPodLogs: %w", err)
	}

	// 첫 packet — request (session_id echo + 매개변수).
	// session_id 가 request 안에 비어있으면 OpenLogStream.session_id 강제 주입.
	req := cfg.Request
	if req.SessionId == "" {
		req.SessionId = cfg.SessionID
	}
	if err := stream.Send(&agentv1.LogPacket{
		Payload: &agentv1.LogPacket_Request{Request: req},
	}); err != nil {
		return fmt.Errorf("send first LogPacket: %w", err)
	}

	// K8s log stream 열기.
	maxDuration := time.Duration(req.GetMaxDurationSeconds()) * time.Second
	if maxDuration <= 0 {
		maxDuration = 10 * time.Minute     // follow 보호용 default
	}
	k8sCtx, k8sCancel := context.WithTimeout(streamCtx, maxDuration)
	defer k8sCancel()

	rc, err := r.kube.StreamPodLogs(k8sCtx, k8s.StreamPodLogsOptions{
		Namespace:    req.GetNamespace(),
		Pod:          req.GetPod(),
		Container:    req.GetContainer(),
		TailLines:    int64(req.GetTailLines()),
		Follow:       req.GetFollow(),
		SinceSeconds: int64(req.GetSinceSeconds()),
		Timestamps:   req.GetTimestamps(),
	})
	if err != nil {
		// K8s 실패 — backend 에는 empty stream + close 로 전달.
		slog.Warn("logstream: k8s stream failed",
			slog.String("session_id", cfg.SessionID),
			slog.String("error", err.Error()))
		_ = stream.CloseSend()
		return fmt.Errorf("k8s stream: %w", err)
	}
	defer func() { _ = rc.Close() }()

	// Backend 가 cancel (response stream complete) 하면 Recv() 가 EOF 반환 → goroutine 이 ctx cancel.
	// agent 의 K8s stream 도 ctx cancel 로 종료.
	go func() {
		for {
			_, recvErr := stream.Recv()
			if recvErr != nil {
				// EOF (정상 종료) 또는 cancel/error — 모두 K8s stream 종료 시그널.
				k8sCancel()
				return
			}
			// 정상 동작에선 backend 가 chunk 보내지 않음. 무시.
		}
	}()

	// 메인 루프 — K8s stream → LogChunk packet.
	buf := make([]byte, maxChunkBytes)
	var bytesSent int64
	var chunksSent int64
	for {
		n, readErr := rc.Read(buf)
		if n > 0 {
			data := make([]byte, n)
			copy(data, buf[:n])
			if sendErr := stream.Send(&agentv1.LogPacket{
				Payload: &agentv1.LogPacket_Chunk{
					Chunk: &agentv1.LogChunk{Data: data},
				},
			}); sendErr != nil {
				slog.Debug("logstream: send chunk failed (likely backend cancel)",
					slog.String("session_id", cfg.SessionID),
					slog.String("error", sendErr.Error()))
				return nil     // backend 가 cancel — 정상 종료
			}
			bytesSent += int64(n)
			chunksSent++
		}
		if readErr == io.EOF {
			// K8s container 종료 또는 follow=false 의 snapshot 완료.
			break
		}
		if readErr != nil {
			// ctx cancel / network — backend 입장에선 stream EOF.
			slog.Debug("logstream: k8s read terminated",
				slog.String("session_id", cfg.SessionID),
				slog.String("error", readErr.Error()))
			break
		}
	}

	if err := stream.CloseSend(); err != nil {
		slog.Debug("logstream: CloseSend error",
			slog.String("session_id", cfg.SessionID),
			slog.String("error", err.Error()))
	}
	slog.Info("logstream completed",
		slog.String("session_id", cfg.SessionID),
		slog.Int64("chunks_sent", chunksSent),
		slog.Int64("bytes_sent", bytesSent))
	return nil
}
