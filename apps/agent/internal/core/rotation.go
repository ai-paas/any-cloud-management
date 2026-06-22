// Identity token rotation.
//
// expires_at < now + RotateThreshold 인지 N 시간마다 체크 → RotateIdentityToken RPC. 성공하면
// stream context cancel → RunStream loop 가 새 token 으로 reconnect.
//
// 실패 시 backoff 차등:
//   - PermissionDenied → token 이미 revoked (critical) — log + 긴 interval (재발급 자체가 망가짐)
//   - 그 외 (network 등) — 짧은 interval 로 retry
package core

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"sync"
	"time"

	agentv1 "anycloud/agent/internal/gen/agent/v1"
	"anycloud/agent/internal/tlsconfig"
	"google.golang.org/grpc"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/metadata"
	"google.golang.org/grpc/status"
)

// TokenStore — 동시 접근 safe 한 현재 identity_token + 만료 시각.
// Stream reconnect 시 최신 값 읽기 위해 사용.
//
// reconnectSignal — Set() 호출 시 channel 에 non-blocking send. 활성 stream 의
// connectAndStream 이 본 신호를 받으면 streamCtx cancel → 즉시 새 token 으로 reconnect.
// 자연 reconnect 까지 며칠 기다리지 않음.
type TokenStore struct {
	mu              sync.RWMutex
	token           string
	expiresAt       time.Time
	reconnectSignal chan struct{}
}

func NewTokenStore(initial string, expiresAt time.Time) *TokenStore {
	return &TokenStore{
		token:           initial,
		expiresAt:       expiresAt,
		reconnectSignal: make(chan struct{}, 1),
	}
}

func (s *TokenStore) Get() (string, time.Time) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.token, s.expiresAt
}

// Set — 새 token 적용 + reconnect 신호 비동기 송신 (channel 가득찼으면 drop — 누락된 신호도 다음
// 활성 stream 가 받음).
func (s *TokenStore) Set(token string, expiresAt time.Time) {
	s.mu.Lock()
	s.token = token
	s.expiresAt = expiresAt
	s.mu.Unlock()
	select {
	case s.reconnectSignal <- struct{}{}:
	default:
	}
}

// ReconnectSignal — connectAndStream 의 select case 가 사용.
func (s *TokenStore) ReconnectSignal() <-chan struct{} {
	return s.reconnectSignal
}

// RotationConfig — timer 동작 파라미터.
type RotationConfig struct {
	// 만료까지 남은 시간이 이 값 이하면 rotation 시도.
	Threshold time.Duration
	// 정기 체크 주기.
	CheckInterval time.Duration
	// Rotate 실패 시 다음 시도까지 대기.
	RetryInterval time.Duration
	// Backend gRPC 주소.
	BackendAddr string
	// 자기 instance id (RotateRequest 의 진단용 필드).
	AgentInstanceID string
	// Transport TLS. zero value = plaintext.
	TLS tlsconfig.Config
}

// DefaultRotationConfig — 합리적 default.
func DefaultRotationConfig() RotationConfig {
	return RotationConfig{
		Threshold:     7 * 24 * time.Hour, // 만료 7일 이내면 rotate
		CheckInterval: 12 * time.Hour,
		RetryInterval: 30 * time.Minute,
	}
}

// RunRotation — context 종료까지 token rotation 유지. 호출자가 별도 goroutine 으로 시작.
//
// onRotated 콜백: 새 token 적용 후 호출. caller 가 현재 stream 의 cancel func 호출 → outer
// RunStream loop 가 reconnect 시 store 에서 새 token 읽어 사용.
//
// identityStore (optional, nil OK): 회전 성공 시 새 token + expires_at 을 K8s Secret 등
// 영구 저장소에 persist. pod restart 후에도 같은 token 으로 부팅 가능. Save 실패는 fatal 아님 —
// 메모리 store 는 이미 갱신됐으니 stream 은 계속 동작, 운영자에게 warn 만 남김.
// clusterID: Secret 의 cluster_id 필드 (debug/audit) 에 함께 기록.
func RunRotation(ctx context.Context, cfg RotationConfig, store *TokenStore,
	identityStore IdentityStore, clusterID string,
	onRotated func(newToken string, expiresAt time.Time)) {
	if store == nil {
		slog.Info("rotation disabled: token store nil")
		return
	}
	if cfg.CheckInterval <= 0 {
		cfg.CheckInterval = 12 * time.Hour
	}
	if cfg.Threshold <= 0 {
		cfg.Threshold = 7 * 24 * time.Hour
	}
	if cfg.RetryInterval <= 0 {
		cfg.RetryInterval = 30 * time.Minute
	}

	slog.Info("identity token rotation started",
		slog.Duration("check_interval", cfg.CheckInterval),
		slog.Duration("threshold", cfg.Threshold))

	// 첫 체크는 즉시 (start-up 시 expires 가 가까운지 점검).
	for {
		nextDelay := cfg.CheckInterval

		_, expiresAt := store.Get()
		needs := !expiresAt.IsZero() && time.Until(expiresAt) <= cfg.Threshold
		if needs {
			newTok, newExp, err := rotateOnce(ctx, cfg, store)
			if err != nil {
				slog.Warn("identity rotation failed (will retry)",
					slog.String("error", err.Error()),
					slog.Duration("retry_after", cfg.RetryInterval))
				nextDelay = cfg.RetryInterval
			} else {
				store.Set(newTok, newExp)
				slog.Info("identity token rotated successfully",
					slog.Time("new_expires_at", newExp))
				// Persist new token before invoking onRotated — 다음 pod restart 가 새 token 으로
				// 부팅하도록. Save 실패는 비치명적이라 stream 진행은 유지.
				if identityStore != nil {
					material := &IdentityMaterial{
						IdentityToken: newTok,
						ExpiresAt:     newExp.UTC().Format(time.RFC3339),
						ClusterId:     clusterID,
					}
					if saveErr := identityStore.Save(ctx, material); saveErr != nil {
						slog.Warn("identity token rotated but Secret save failed",
							slog.String("error", saveErr.Error()))
					}
				}
				if onRotated != nil {
					onRotated(newTok, newExp)
				}
			}
		}

		select {
		case <-ctx.Done():
			slog.Info("identity rotation stopped")
			return
		case <-time.After(nextDelay):
		}
	}
}

// rotateOnce — 단일 rotation 시도. Bearer 의 현재 token 으로 RotateIdentityToken RPC 호출.
func rotateOnce(ctx context.Context, cfg RotationConfig, store *TokenStore) (string, time.Time, error) {
	if cfg.BackendAddr == "" {
		return "", time.Time{}, errors.New("BackendAddr required")
	}
	current, _ := store.Get()
	if current == "" {
		return "", time.Time{}, errors.New("no current token to rotate")
	}

	dialCtx, cancel := context.WithTimeout(ctx, 10*time.Second)
	defer cancel()
	credsOpt, err := cfg.TLS.DialOption(cfg.BackendAddr)
	if err != nil {
		return "", time.Time{}, fmt.Errorf("build TLS dial option: %w", err)
	}
	conn, err := grpc.DialContext(dialCtx, cfg.BackendAddr,
		credsOpt,
		grpc.WithBlock())
	if err != nil {
		return "", time.Time{}, fmt.Errorf("dial: %w", err)
	}
	defer func() { _ = conn.Close() }()

	md := metadata.Pairs("authorization", "Bearer "+current)
	rpcCtx, rpcCancel := context.WithTimeout(metadata.NewOutgoingContext(ctx, md), 15*time.Second)
	defer rpcCancel()

	resp, err := agentv1.NewAgentBootstrapClient(conn).RotateIdentityToken(rpcCtx,
		&agentv1.RotateRequest{AgentInstanceId: cfg.AgentInstanceID})
	if err != nil {
		// PERMISSION_DENIED 는 critical — 현재 token 이 revoke 됐을 가능성. caller 는 backoff 후
		// 다시 시도해도 같은 결과지만, agent 가 retry loop 에서 외부 운영자 개입 (re-register) 까지 기다림.
		st, _ := status.FromError(err)
		if st != nil && st.Code() == codes.PermissionDenied {
			return "", time.Time{}, fmt.Errorf("rotation denied (token likely revoked): %s", st.Message())
		}
		return "", time.Time{}, fmt.Errorf("RotateIdentityToken: %w", err)
	}

	newToken := resp.GetNewIdentityToken()
	if newToken == "" {
		return "", time.Time{}, errors.New("empty new_identity_token in response")
	}
	exp, perr := time.Parse(time.RFC3339, resp.GetExpiresAt())
	if perr != nil {
		// 미파싱 — 기본 30 일로 fallback (그래도 어느 정도 valid 한 상태로 처리).
		exp = time.Now().Add(30 * 24 * time.Hour)
	}
	return newToken, exp, nil
}
