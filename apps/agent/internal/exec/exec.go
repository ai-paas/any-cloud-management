// Package exec — PodExec orchestrator.
//
// 흐름 (agent 측):
//  1. 메인 Stream RPC 가 ControlMessage{OpenExecSession} 수신.
//  2. 본 패키지의 Run() 호출 → AgentRuntime/PodExec 새 bidi stream 을 backend 로 open.
//  3. 첫 ExecPacket 으로 ExecRequest 전송 (session_id echo 포함).
//  4. AllowList 의 ExecNamespaces 검증 — 거부면 ExecPacket{End: PERMISSION_DENIED} 후 close.
//  5. k8s.Client.ExecInPod 호출 — stdin/stdout/stderr 는 io.Pipe 로 연결.
//  6. Pump goroutines:
//     - backend → agent: incoming ExecPacket 의 StdinData → pod stdin
//     - pod stdout/stderr → backend: ExecPacket{StdoutData/StderrData}
//     - incoming Resize → TerminalSizeQueue
//  7. ExecInPod 가 종료되면 ExecPacket{End: ExecStatus} 전송 + CloseSend.
//
// Concurrency:
//   - Send 는 동시 호출 불가 (gRPC stream 제약) — sendMu 로 직렬화.
//   - Recv 는 단일 goroutine (recvLoop).
//   - Pod stdout/stderr 는 각각 별도 goroutine 으로 읽어 ExecPacket 으로 send.
package exec

import (
	"context"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"sync"
	"time"

	"anycloud/agent/internal/config"
	agentv1 "anycloud/agent/internal/gen/agent/v1"
	"anycloud/agent/internal/k8s"
	"anycloud/agent/internal/tlsconfig"
	"google.golang.org/grpc"
	"google.golang.org/grpc/metadata"
	"k8s.io/client-go/tools/remotecommand"
)

// SessionConfig — Runner.Run 에 전달되는 한 exec 세션의 모든 입력.
type SessionConfig struct {
	BackendAddr        string
	AgentIdentityToken string
	SessionID          string
	Request            *agentv1.ExecRequest
	// Transport TLS. zero value = plaintext.
	TLS tlsconfig.Config
}

// Runner — exec 세션을 실행. K8s client + AllowList loader 를 보유.
type Runner struct {
	kube      k8s.Client
	allowlist *config.Loader
}

// New — Runner 생성.
func New(kube k8s.Client, allowlist *config.Loader) *Runner {
	return &Runner{kube: kube, allowlist: allowlist}
}

// Run — context 종료 또는 exec 종료까지 블록. 호출은 별도 goroutine 권장.
//
// 에러 케이스:
//   - dial 실패 → 즉시 error 반환 (backend 측에 ExecStatus 전달 불가).
//   - K8s exec 실패 → ExecPacket{End} 로 backend 에 통지 후 nil 반환.
//   - AllowList 거부 → ExecPacket{End: PERMISSION_DENIED} 후 nil 반환.
func (r *Runner) Run(ctx context.Context, cfg SessionConfig) error {
	if cfg.Request == nil {
		return errors.New("exec.Run: request required")
	}
	if cfg.BackendAddr == "" {
		return errors.New("exec.Run: backend addr required")
	}
	if cfg.AgentIdentityToken == "" {
		return errors.New("exec.Run: identity token required")
	}

	// AllowList 검증 — namespace 거부 시 stream 열기 전에 단순 로그 + skip 도 가능하지만,
	// backend 가 user 에게 알리려면 stream 으로 ExecStatus 전달이 필요. 그래서 dial 후 first packet 으로 처리.
	credsOpt, err := cfg.TLS.DialOption(cfg.BackendAddr)
	if err != nil {
		return fmt.Errorf("build TLS dial option: %w", err)
	}
	conn, err := grpc.NewClient(cfg.BackendAddr, credsOpt)
	if err != nil {
		return fmt.Errorf("dial backend for exec: %w", err)
	}
	defer func() { _ = conn.Close() }()

	client := agentv1.NewAgentRuntimeClient(conn)
	md := metadata.Pairs("authorization", "Bearer "+cfg.AgentIdentityToken)
	streamCtx, cancel := context.WithCancel(metadata.NewOutgoingContext(ctx, md))
	defer cancel()

	stream, err := client.PodExec(streamCtx)
	if err != nil {
		return fmt.Errorf("open PodExec stream: %w", err)
	}

	sess := newSession(stream, cfg.SessionID, cfg.Request)

	// First packet: echo ExecRequest with session_id.
	if err := sess.send(&agentv1.ExecPacket{
		Payload: &agentv1.ExecPacket_Request{Request: cfg.Request},
	}); err != nil {
		return fmt.Errorf("send first ExecRequest: %w", err)
	}

	// AllowList check — 거부 시 End packet + close. 짧은 경로 — server 가 buffered packet 처리할
	// 시간을 주기 위해 drainServerEOF 으로 io.EOF 까지 대기.
	if r.allowlist != nil {
		snap := r.allowlist.Snapshot()
		if !snap.IsExecNamespaceAllowed(cfg.Request.GetNamespace()) {
			slog.Warn("exec: namespace denied by allowlist",
				slog.String("session_id", cfg.SessionID),
				slog.String("namespace", cfg.Request.GetNamespace()))
			_ = sess.sendEnd(0, "namespace not allowed: "+cfg.Request.GetNamespace(), "NAMESPACE_DENIED")
			_ = stream.CloseSend()
			drainServerEOF(stream, 2*time.Second)
			return nil
		}
		if !snap.IsCommandAllowed("EXEC_POD") {
			slog.Warn("exec: EXEC_POD command not in allowlist",
				slog.String("session_id", cfg.SessionID))
			_ = sess.sendEnd(0, "exec command not allowed", "PERMISSION_DENIED")
			_ = stream.CloseSend()
			drainServerEOF(stream, 2*time.Second)
			return nil
		}
	}

	// I/O pipes — k8s.ExecInPod 가 io.Reader/Writer 받아야 함.
	stdinR, stdinW := io.Pipe()
	stdoutR, stdoutW := io.Pipe()
	stderrR, stderrW := io.Pipe()

	resizeQ := newResizeQueue(streamCtx)

	// Recv loop — backend → agent.
	recvDone := make(chan struct{})
	go func() {
		defer close(recvDone)
		sess.recvLoop(stdinW, resizeQ)
		// stdin pipe close 시 ExecInPod 의 stdin reader 가 EOF — pod 가 자연 종료 가능.
		_ = stdinW.Close()
	}()

	// Pump stdout / stderr → backend.
	pumpDone := make(chan struct{}, 2)
	go pumpToBackend(stdoutR, agentv1.ExecPacket_StdoutData{}, sess, pumpDone)
	go pumpToBackend(stderrR, agentv1.ExecPacket_StderrData{}, sess, pumpDone)

	// K8s exec — 블록.
	execErr := r.kube.ExecInPod(streamCtx, k8s.PodExecOptions{
		Namespace: cfg.Request.GetNamespace(),
		Pod:       cfg.Request.GetPod(),
		Container: cfg.Request.GetContainer(),
		Command:   cfg.Request.GetCommand(),
		TTY:       cfg.Request.GetTty(),
		Stdin:     cfg.Request.GetStdin(),
	}, k8s.ExecStreams{
		Stdin:       stdinR,
		Stdout:      stdoutW,
		Stderr:      stderrW,
		ResizeQueue: resizeQ,
	})

	// Close write ends so pump goroutines see EOF.
	_ = stdoutW.Close()
	_ = stderrW.Close()
	// Drain pumps BEFORE sendEnd — preserve packet ordering (no stdout after End).
	<-pumpDone
	<-pumpDone

	// Send final ExecStatus while stream context still alive.
	exitCode, msg, code := classifyExecError(execErr)
	if err := sess.sendEnd(exitCode, msg, code); err != nil {
		slog.Warn("exec: send final ExecStatus failed",
			slog.String("session_id", cfg.SessionID),
			slog.String("error", err.Error()))
	}
	_ = stream.CloseSend()

	// CloseSend → server eventually EOFs the recv side → recvLoop exits.
	// Don't cancel(): cancellation races with packet delivery (cancels the stream before
	// buffered Sends reach the server). Use a wait timeout as safety net.
	select {
	case <-recvDone:
	case <-time.After(5 * time.Second):
		slog.Debug("exec: recvLoop did not exit within 5s, forcing cancel",
			slog.String("session_id", cfg.SessionID))
		cancel()
		<-recvDone
	}
	return nil
}

// pumpToBackend — io.Reader (pod stdout/stderr) 에서 읽어 ExecPacket 으로 backend 송신.
// flavor 는 stdout/stderr 구분용 zero-value (타입만 사용).
func pumpToBackend(r io.Reader, flavor any, sess *session, done chan<- struct{}) {
	defer func() { done <- struct{}{} }()
	buf := make([]byte, 8*1024)
	for {
		n, err := r.Read(buf)
		if n > 0 {
			cp := make([]byte, n)
			copy(cp, buf[:n])
			var pkt *agentv1.ExecPacket
			switch flavor.(type) {
			case agentv1.ExecPacket_StdoutData:
				pkt = &agentv1.ExecPacket{Payload: &agentv1.ExecPacket_StdoutData{StdoutData: cp}}
			case agentv1.ExecPacket_StderrData:
				pkt = &agentv1.ExecPacket{Payload: &agentv1.ExecPacket_StderrData{StderrData: cp}}
			}
			if pkt == nil {
				continue
			}
			if sendErr := sess.send(pkt); sendErr != nil {
				slog.Debug("exec pump: send failed (stream likely closed)",
					slog.String("error", sendErr.Error()))
				return
			}
		}
		if err != nil {
			if !errors.Is(err, io.EOF) && !errors.Is(err, io.ErrClosedPipe) {
				slog.Debug("exec pump: read error",
					slog.String("error", err.Error()))
			}
			return
		}
	}
}

// session — 단일 PodExec stream 의 send-side mutex 보유.
type session struct {
	stream    agentv1.AgentRuntime_PodExecClient
	sessionID string
	request   *agentv1.ExecRequest
	sendMu    sync.Mutex
}

func newSession(stream agentv1.AgentRuntime_PodExecClient, sessionID string, req *agentv1.ExecRequest) *session {
	return &session{stream: stream, sessionID: sessionID, request: req}
}

func (s *session) send(pkt *agentv1.ExecPacket) error {
	s.sendMu.Lock()
	defer s.sendMu.Unlock()
	return s.stream.Send(pkt)
}

func (s *session) sendEnd(exitCode int32, msg, code string) error {
	return s.send(&agentv1.ExecPacket{
		Payload: &agentv1.ExecPacket_End{
			End: &agentv1.ExecStatus{
				ExitCode:  exitCode,
				Message:   msg,
				ErrorCode: code,
			},
		},
	})
}

// recvLoop — backend → agent ExecPacket 수신.
//   - StdinData → stdinW.Write
//   - Resize → resizeQ.push
//   - Request/Stdout/Stderr/End → skip (client→agent 방향 무관)
//   - EOF → return (stream closed by backend)
func (s *session) recvLoop(stdinW io.Writer, resizeQ *resizeQueue) {
	for {
		pkt, err := s.stream.Recv()
		if err != nil {
			if !errors.Is(err, io.EOF) {
				slog.Debug("exec recv loop: stream ended",
					slog.String("error", err.Error()))
			}
			return
		}
		switch p := pkt.GetPayload().(type) {
		case *agentv1.ExecPacket_StdinData:
			if _, werr := stdinW.Write(p.StdinData); werr != nil {
				slog.Debug("exec recv: stdin write failed",
					slog.String("error", werr.Error()))
				return
			}
		case *agentv1.ExecPacket_Resize:
			resizeQ.push(remotecommand.TerminalSize{
				Width:  uint16(p.Resize.GetCols()),
				Height: uint16(p.Resize.GetRows()),
			})
		default:
			// Request/Stdout/Stderr/End — agent 가 받을 일 없음. 무시.
		}
	}
}

// drainServerEOF — CloseSend 후 server-side 가 stream 닫을 때까지 읽기 (또는 timeout).
//
// Why: gRPC client 가 stream context 를 cancel 하면 server 가 buffered Send 들을 보기 전에
// stream 이 abort 됨. CloseSend → server 가 io.EOF 받음 → Recv loop 종료 → server 가 stream
// close → client 의 Recv 가 io.EOF 받음. 이때까지 대기해야 packet delivery 보장.
func drainServerEOF(stream agentv1.AgentRuntime_PodExecClient, max time.Duration) {
	done := make(chan struct{})
	go func() {
		defer close(done)
		for {
			if _, err := stream.Recv(); err != nil {
				return
			}
		}
	}()
	select {
	case <-done:
	case <-time.After(max):
	}
}

// classifyExecError — k8s exec 결과를 ExecStatus 필드로 매핑.
func classifyExecError(err error) (exitCode int32, message string, code string) {
	if err == nil {
		return 0, "", ""
	}
	// client-go 의 exec.CodeExitError 를 unwrap. 만약 직접 type assert 가능하면 exit code 추출.
	type codeExitErr interface{ ExitStatus() int }
	var cee codeExitErr
	if errors.As(err, &cee) {
		return int32(cee.ExitStatus()), err.Error(), "EXIT_NONZERO"
	}
	return -1, err.Error(), "EXEC_FAILED"
}
