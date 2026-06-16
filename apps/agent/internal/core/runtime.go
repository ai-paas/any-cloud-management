// Runtime stream client — bootstrap 이후 identity_token 으로 backend gRPC 에 bidi stream 연결.
//
// 핵심 동작:
//   1. gRPC dial (insecure; mTLS 예정)
//   2. AgentRuntime.Stream 호출 → bidi stream open
//   3. Goroutine 1: backend → agent 의 ControlMessage 수신 → Dispatcher.Handle → AgentMessage send
//   4. Goroutine 2: 주기적 heartbeat send (30s interval)
//   5. Stream 끊기면 exponential backoff 로 reconnect
//
// Reconnect 정책:
//   - initial backoff 1s, multiplier 2, max 30s, jitter ±10%
//   - context.Done() 시 종료 (SIGTERM)
package core

import (
	"context"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"math/rand"
	"time"

	"anycloud/agent/internal/controller"
	execpkg "anycloud/agent/internal/exec"
	agentv1 "anycloud/agent/internal/gen/agent/v1"
	"anycloud/agent/internal/k8s"
	logstreampkg "anycloud/agent/internal/logstream"
	"anycloud/agent/internal/rbacwatcher"
	"anycloud/agent/internal/tlsconfig"
	"google.golang.org/grpc"
	"google.golang.org/grpc/metadata"
	"google.golang.org/protobuf/types/known/timestamppb"
)

// RuntimeConfig — main 에서 env / bootstrap 결과로 채워 전달.
type RuntimeConfig struct {
	BackendAddr        string
	AgentIdentityToken string
	AgentInstanceID    string

	// 옵션. 비어있지 않으면 매 reconnect 시 store 에서 최신 token 읽음 (rotation 지원).
	// nil 이면 AgentIdentityToken 값 그대로 사용 (기존 동작).
	TokenStore *TokenStore

	HeartbeatInterval time.Duration
	DialTimeout       time.Duration

	// Reconnect backoff.
	InitialBackoff time.Duration
	MaxBackoff     time.Duration
	BackoffMult    float64

	// Transport TLS. 비활성 (zero value) 면 plaintext. main 에서 env 로 채워 전달.
	TLS tlsconfig.Config
}

// currentToken — RuntimeConfig 에 TokenStore 있으면 그 값, 없으면 AgentIdentityToken.
func (c RuntimeConfig) currentToken() string {
	if c.TokenStore != nil {
		t, _ := c.TokenStore.Get()
		if t != "" {
			return t
		}
	}
	return c.AgentIdentityToken
}

// DefaultRuntimeConfig — production 권장값.
func DefaultRuntimeConfig() RuntimeConfig {
	return RuntimeConfig{
		HeartbeatInterval: 30 * time.Second,
		DialTimeout:       10 * time.Second,
		InitialBackoff:    1 * time.Second,
		MaxBackoff:        30 * time.Second,
		BackoffMult:       2.0,
	}
}

// RunStream 은 context 종료까지 stream 을 유지. 끊기면 backoff 후 재시도.
// execRunner 가 nil 이면 PodExec 요청 시 PERMISSION_DENIED 응답.
// logRunner 가 nil 이면 OpenLogStream 요청 시 무시 (log streaming 비활성).
// kube 가 nil 이면 heartbeat 의 gpu_node_count 항상 0 (k8s 호출 불가).
func RunStream(ctx context.Context, cfg RuntimeConfig, dispatcher *controller.Dispatcher,
	execRunner *execpkg.Runner, logRunner *logstreampkg.Runner, kube k8s.Client) error {
	if cfg.AgentIdentityToken == "" {
		return errors.New("agent_identity_token required for runtime stream")
	}
	if cfg.BackendAddr == "" {
		return errors.New("BACKEND_GRPC_ADDR required")
	}

	// GPU 노드 카운트 캐시. stream lifecycle 전체 공유 (reconnect 시에도 캐시 유지).
	gpuCounter := newGpuNodeCounter(kube)

	backoff := cfg.InitialBackoff
	for {
		err := connectAndStream(ctx, cfg, dispatcher, execRunner, logRunner, gpuCounter, kube)
		if ctx.Err() != nil {
			return ctx.Err()
		}
		if err != nil {
			slog.Warn("runtime stream error — reconnecting",
				slog.String("error", err.Error()),
				slog.Duration("backoff", backoff))
		}
		// Sleep with cancellation.
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-time.After(jitter(backoff)):
		}
		backoff = nextBackoff(backoff, cfg.MaxBackoff, cfg.BackoffMult)
	}
}

// connectAndStream 은 한 번의 stream 세션 — dial / stream open / send-recv loops 까지.
func connectAndStream(ctx context.Context, cfg RuntimeConfig, dispatcher *controller.Dispatcher,
	execRunner *execpkg.Runner, logRunner *logstreampkg.Runner, gpuCounter *gpuNodeCounter,
	kube k8s.Client) error {
	dialCtx, cancel := context.WithTimeout(ctx, cfg.DialTimeout)
	defer cancel()

	credsOpt, err := cfg.TLS.DialOption(cfg.BackendAddr)
	if err != nil {
		return fmt.Errorf("build TLS dial option: %w", err)
	}
	conn, err := grpc.DialContext(dialCtx, cfg.BackendAddr,
		credsOpt,
		grpc.WithBlock(),
		// backend 가 chart .tgz tarball (base64) 을 INSTALL_ADDON 으로 push 가능해
		// inbound 16 MB 까지 수용 (default 4 MB → 일반 chart 는 안에 들어가지만 큰 chart 대비).
		grpc.WithDefaultCallOptions(grpc.MaxCallRecvMsgSize(16*1024*1024)),
	)
	if err != nil {
		return fmt.Errorf("dial backend: %w", err)
	}
	defer func() { _ = conn.Close() }()

	client := agentv1.NewAgentRuntimeClient(conn)
	// 매 reconnect 마다 TokenStore 에서 최신 token 읽음.
	md := metadata.Pairs("authorization", "Bearer "+cfg.currentToken())
	streamCtx, streamCancel := context.WithCancel(metadata.NewOutgoingContext(ctx, md))
	defer streamCancel()

	stream, err := client.Stream(streamCtx)
	if err != nil {
		return fmt.Errorf("open stream: %w", err)
	}

	slog.Info("runtime stream open", slog.String("backend", cfg.BackendAddr))

	// Token rotation force-reconnect watcher — rotation 직후 TokenStore.Set 의 signal 수신 시
	// stream cancel → 즉시 새 token 으로 reconnect.
	if cfg.TokenStore != nil {
		go func() {
			select {
			case <-streamCtx.Done():
				return
			case <-cfg.TokenStore.ReconnectSignal():
				slog.Info("token rotation reconnect signal received — closing current stream")
				streamCancel()
			}
		}()
	}

	// 세 goroutine: receive + heartbeat + (선택) rbac watch.
	// RBAC binding watch 는 K8s client 있을 때만. 변경 시 backend cache invalidate.
	// stream.Send 는 multi-goroutine 호출 가능 (gRPC 가 internal lock).
	rbacEnabled := kube != nil && kube.Clientset() != nil
	errChCap := 2
	if rbacEnabled {
		errChCap = 3
	}
	errCh := make(chan error, errChCap)
	sender := &streamEventSender{stream: stream}
	go func() { errCh <- runReceiveLoop(streamCtx, stream, dispatcher, cfg, execRunner, logRunner) }()
	go func() { errCh <- runHeartbeatLoop(streamCtx, stream, cfg.HeartbeatInterval, gpuCounter) }()
	if rbacEnabled {
		go func() { errCh <- rbacwatcher.Run(streamCtx, kube.Clientset(), sender) }()
	}

	err = <-errCh
	streamCancel()
	// Drain remaining goroutines.
	for i := 0; i < errChCap-1; i++ {
		<-errCh
	}
	if errors.Is(err, io.EOF) {
		return errors.New("backend closed stream (EOF)")
	}
	return err
}

// runReceiveLoop 은 backend → agent ControlMessage 수신 + dispatch.
func runReceiveLoop(ctx context.Context, stream agentv1.AgentRuntime_StreamClient,
	dispatcher *controller.Dispatcher, cfg RuntimeConfig, execRunner *execpkg.Runner,
	logRunner *logstreampkg.Runner) error {
	for {
		msg, err := stream.Recv()
		if err != nil {
			return fmt.Errorf("recv: %w", err)
		}
		if err := handleControl(ctx, stream, dispatcher, cfg, execRunner, logRunner, msg); err != nil {
			slog.Error("control message handler error",
				slog.String("request_id", msg.GetRequestId()),
				slog.String("error", err.Error()))
			// 개별 메시지 에러는 stream 을 끊지 않는다.
		}
	}
}

func handleControl(ctx context.Context, stream agentv1.AgentRuntime_StreamClient,
	dispatcher *controller.Dispatcher, cfg RuntimeConfig, execRunner *execpkg.Runner,
	logRunner *logstreampkg.Runner, msg *agentv1.ControlMessage) error {
	switch msg.GetPayload().(type) {
	case *agentv1.ControlMessage_Command:
		resp := dispatcher.Handle(msg.GetCommand())
		return stream.Send(&agentv1.AgentMessage{
			RequestId: msg.GetRequestId(),
			Payload: &agentv1.AgentMessage_Response{
				Response: resp,
			},
		})
	case *agentv1.ControlMessage_Heartbeat:
		// Backend → agent heartbeat — agent 응답 비대칭이라 별도 처리 없이 기록만.
		slog.Debug("heartbeat from backend")
		return nil
	case *agentv1.ControlMessage_Shutdown:
		slog.Info("shutdown signal from backend",
			slog.String("reason", msg.GetShutdown().GetReason()))
		// caller 가 stream EOF 로 처리할 수 있도록 nil 반환.
		return nil
	case *agentv1.ControlMessage_OpenExecSession:
		// 별도 PodExec stream 을 backend 로 신규 open. 메인 Stream 은 그대로 유지.
		open := msg.GetOpenExecSession()
		if execRunner == nil {
			slog.Warn("exec: OpenExecSession received but runner not configured",
				slog.String("session_id", open.GetSessionId()))
			return nil
		}
		req := open.GetRequest()
		if req == nil {
			slog.Warn("exec: OpenExecSession missing request",
				slog.String("session_id", open.GetSessionId()))
			return nil
		}
		// session_id 가 비어있으면 OpenExecSession 의 값을 강제 주입 (proto 의 동봉 안내).
		if req.GetSessionId() == "" {
			req.SessionId = open.GetSessionId()
		}
		go func() {
			err := execRunner.Run(ctx, execpkg.SessionConfig{
				BackendAddr:        cfg.BackendAddr,
				AgentIdentityToken: cfg.currentToken(),
				SessionID:          open.GetSessionId(),
				Request:            req,
				TLS:                cfg.TLS,
			})
			if err != nil {
				slog.Warn("exec session terminated with error",
					slog.String("session_id", open.GetSessionId()),
					slog.String("error", err.Error()))
			}
		}()
		return nil
	case *agentv1.ControlMessage_OpenLogStream:
		// PodExec 과 동일 패턴 — 별도 StreamPodLogs bidi 를 신규 open.
		open := msg.GetOpenLogStream()
		if logRunner == nil {
			slog.Warn("logstream: OpenLogStream received but runner not configured",
				slog.String("session_id", open.GetSessionId()))
			return nil
		}
		req := open.GetRequest()
		if req == nil {
			slog.Warn("logstream: OpenLogStream missing request",
				slog.String("session_id", open.GetSessionId()))
			return nil
		}
		if req.GetSessionId() == "" {
			req.SessionId = open.GetSessionId()
		}
		go func() {
			err := logRunner.Run(ctx, logstreampkg.SessionConfig{
				BackendAddr:        cfg.BackendAddr,
				AgentIdentityToken: cfg.currentToken(),
				SessionID:          open.GetSessionId(),
				Request:            req,
				TLS:                cfg.TLS,
			})
			if err != nil {
				slog.Warn("logstream session terminated with error",
					slog.String("session_id", open.GetSessionId()),
					slog.String("error", err.Error()))
			}
		}()
		return nil
	}
	slog.Debug("control message with unknown payload")
	return nil
}

// runHeartbeatLoop 은 주기적으로 agent → backend heartbeat 송신. gpuCounter 가 nil 이 아니면
// 매 heartbeat 마다 캐시된 GPU 노드 수 piggy-back .
func runHeartbeatLoop(ctx context.Context, stream agentv1.AgentRuntime_StreamClient,
	interval time.Duration, gpuCounter *gpuNodeCounter) error {
	if interval <= 0 {
		// Heartbeat 비활성 (test 용).
		<-ctx.Done()
		return ctx.Err()
	}
	ticker := time.NewTicker(interval)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-ticker.C:
			var gpuCount int32 = 0
			if gpuCounter != nil {
				gpuCount = gpuCounter.get(ctx)
			}
			err := stream.Send(&agentv1.AgentMessage{
				Payload: &agentv1.AgentMessage_Heartbeat{
					Heartbeat: &agentv1.Heartbeat{
						SentAt: timestamppb.Now(),
						Health: &agentv1.AgentHealth{
							LastK8SApiOk: timestamppb.Now(),
							GpuNodeCount: gpuCount,
						},
					},
				},
			})
			if err != nil {
				return fmt.Errorf("heartbeat send: %w", err)
			}
		}
	}
}

// streamEventSender — runtime stream 의 AgentEvent send 추상화 (rbacwatcher.EventSender 구현).
type streamEventSender struct {
	stream agentv1.AgentRuntime_StreamClient
}

func (s *streamEventSender) SendEvent(event *agentv1.AgentEvent) error {
	return s.stream.Send(&agentv1.AgentMessage{
		Payload: &agentv1.AgentMessage_Event{Event: event},
	})
}

func nextBackoff(current, max time.Duration, mult float64) time.Duration {
	next := time.Duration(float64(current) * mult)
	if next > max {
		return max
	}
	return next
}

func jitter(d time.Duration) time.Duration {
	// ±10% jitter — thundering herd 방지.
	delta := float64(d) * 0.1
	offset := (rand.Float64()*2 - 1) * delta
	result := d + time.Duration(offset)
	if result < 0 {
		return d
	}
	return result
}
