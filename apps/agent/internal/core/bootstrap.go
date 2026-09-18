// Package core — agent 의 핵심 흐름 (bootstrap / runtime stream / event publish).
//
// Bootstrap 은 short-lived registration_token (env) 으로 Register RPC 호출 → 60일 opaque
// identity_token 수령 → K8s Secret 영구 저장. Rancher 와 동일한 bearer-over-TLS (mTLS 미사용)
// — Secret 보관으로 pod restart 안정성 확보.

package core

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"strings"
	"time"

	agentv1 "anycloud/agent/internal/gen/agent/v1"
	"anycloud/agent/internal/tlsconfig"
	"google.golang.org/grpc"
	"google.golang.org/grpc/metadata"
)

// BootstrapConfig — agent main 이 env 에서 읽어 전달.
type BootstrapConfig struct {
	BackendAddr       string
	RegistrationToken string

	// 클러스터 식별 정보 — client-go 로 동적 수집하거나 env fallback.
	KubernetesUID     string
	KubernetesVersion string
	Distribution      string
	APIServerEndpoint string
	// ServerCA — in-cluster service account ca.crt (PEM). backend 가 AGENT_PENDING placeholder
	// cluster row 에 backfill 하는 데 사용. 비어있어도 호환 (선택).
	ServerCA string

	// Agent 자체 정보.
	AgentInstanceID string
	AgentVersion    string
	PodName         string

	// Network info (optional).
	PublicIP  string
	PrivateIP string

	DialTimeout     time.Duration
	RegisterTimeout time.Duration

	// Transport TLS. zero value = plaintext. mTLS 가 아닌 server-side TLS 만 (Rancher 와 동일).
	TLS tlsconfig.Config
}

// BootstrapResult — Register 성공 시 backend 가 돌려준 정보.
type BootstrapResult struct {
	ClusterID          string
	AgentIdentityToken string
	ExpiresAt          string
	ClusterStatus      agentv1.ClusterStatus
	/*
	 * identity 가 Secret 에 남았는지. false 면 이 파드는 메모리 token 으로만 돌고, 다음 재시작
	 * 때 새 등록 토큰이 없으면 기동하지 못한다.
	 */
	IdentityPersisted bool
}

// BootstrapIdentity — agent startup 의 single entry point.
//
// Secret 에 valid identity 가 있으면 Register 건너뛰고 즉시 반환 (REGISTRATION_TOKEN env
// 불필요). 없거나 만료 임박 (graceMin 이내) 이면 Run() 으로 새로 Register 후 Save.
//
// 동시성: startup 의 단일 goroutine 에서만 호출. rotation goroutine 은 (RunRotation →
// onRotated → store.Save) 별도 경로로 같은 store 에 write — race 는 K8s API 의 optimistic
// concurrency 가 해결.
func BootstrapIdentity(ctx context.Context, cfg BootstrapConfig, store IdentityStore,
	graceMin time.Duration) (*BootstrapResult, error) {
	if store != nil {
		existing, err := store.Load(ctx)
		if err != nil {
			// Load 실패 시 fail-safe = bootstrap 진행 (token 없는 상태로 간주). 단, Secret API 에러를
			// 그대로 무시하지 않고 warn 로그 남김 — 운영자가 RBAC/연결 문제 발견.
			slog.Warn("identity store Load failed — falling through to Register",
				slog.String("error", err.Error()))
		} else if existing.IsValid(time.Now(), graceMin) {
			slog.Info("identity token loaded from secret — skipping Register",
				slog.String("cluster_id", existing.ClusterId),
				slog.String("expires_at", existing.ExpiresAt))
			return &BootstrapResult{
				IdentityPersisted:  true,
				ClusterID:          existing.ClusterId,
				AgentIdentityToken: existing.IdentityToken,
				ExpiresAt:          existing.ExpiresAt,
				ClusterStatus:      agentv1.ClusterStatus_CLUSTER_STATUS_ACTIVE,
			}, nil
		} else if existing != nil {
			slog.Info("identity token expired or near expiry — re-registering",
				slog.String("expires_at", existing.ExpiresAt),
				slog.Duration("grace", graceMin))
		}
	}

	// Fallthrough: 정상 bootstrap (Register RPC) 수행.
	result, err := Run(ctx, cfg)
	if err != nil {
		return nil, err
	}
	if store != nil && result.AgentIdentityToken != "" {
		material := &IdentityMaterial{
			IdentityToken: result.AgentIdentityToken,
			ExpiresAt:     result.ExpiresAt,
			ClusterId:     result.ClusterID,
		}
		if saveErr := store.Save(ctx, material); saveErr != nil {
			/*
			 * 지금은 메모리 token 으로 잘 돈다. 문제는 다음 재시작 때 드러나는데, 그때는 등록
			 * 토큰(10분 TTL)이 이미 만료돼 있어 파드가 CrashLoop 으로 빠진다. 지금 눈에 띄지
			 * 않으면 아무도 모르므로 error 로 올린다.
			 */
			slog.Error("identity store Save failed — 다음 재시작 때 새 REGISTRATION_TOKEN 이 없으면 기동하지 못한다",
				slog.String("error", saveErr.Error()),
				slog.String("secret", "cluster-agent-identity"))
			result.IdentityPersisted = false
			return result, nil
		}
		result.IdentityPersisted = true
	}
	return result, nil
}

// Run 은 dial + Register 를 한 번 수행. 실패 시 error 반환 — caller 가 retry 정책 결정.
func Run(ctx context.Context, cfg BootstrapConfig) (*BootstrapResult, error) {
	if cfg.RegistrationToken == "" {
		return nil, errors.New("REGISTRATION_TOKEN env required for bootstrap")
	}
	if cfg.BackendAddr == "" {
		return nil, errors.New("BACKEND_GRPC_ADDR env required")
	}

	dialCtx, cancel := context.WithTimeout(ctx, cfg.DialTimeout)
	defer cancel()

	credsOpt, err := cfg.TLS.DialOption(cfg.BackendAddr)
	if err != nil {
		return nil, fmt.Errorf("build TLS dial option: %w", err)
	}
	conn, err := grpc.DialContext(dialCtx, cfg.BackendAddr,
		credsOpt,
		grpc.WithBlock(),
	)
	if err != nil {
		return nil, fmt.Errorf("dial backend %s: %w", cfg.BackendAddr, err)
	}
	defer func() {
		_ = conn.Close()
	}()

	client := agentv1.NewAgentBootstrapClient(conn)

	// Authorization: Bearer <registration_token>.
	md := metadata.Pairs("authorization", "Bearer "+cfg.RegistrationToken)
	registerCtx, registerCancel := context.WithTimeout(metadata.NewOutgoingContext(ctx, md), cfg.RegisterTimeout)
	defer registerCancel()

	req := &agentv1.RegisterRequest{
		Cluster: &agentv1.ClusterIdentity{
			K8SClusterUid: cfg.KubernetesUID,
			Version:       cfg.KubernetesVersion,
			Distribution:  cfg.Distribution,
			Endpoint:      cfg.APIServerEndpoint,
			ServerCa:      cfg.ServerCA,
		},
		Agent: &agentv1.AgentIdentity{
			AgentInstanceId: cfg.AgentInstanceID,
			Version:         cfg.AgentVersion,
			PodName:         cfg.PodName,
		},
		Network: &agentv1.NetworkInfo{
			PublicIp:  cfg.PublicIP,
			PrivateIp: cfg.PrivateIP,
		},
	}

	slog.Info("bootstrap: dialing backend", slog.String("addr", cfg.BackendAddr))
	resp, err := client.Register(registerCtx, req)
	if err != nil {
		return nil, fmt.Errorf("Register RPC failed: %w", err)
	}

	slog.Info("bootstrap: registered",
		slog.String("cluster_id", resp.GetClusterId()),
		slog.String("expires_at", resp.GetExpiresAt()),
		slog.String("status", resp.GetClusterStatus().String()))

	return &BootstrapResult{
		ClusterID:          resp.GetClusterId(),
		AgentIdentityToken: resp.GetAgentIdentityToken(),
		ExpiresAt:          resp.GetExpiresAt(),
		ClusterStatus:      resp.GetClusterStatus(),
	}, nil
}

// RegistrationTokenExpired — JWT 의 exp 가 지났는지.
//
// 서명은 검증하지 않는다. 서버가 거부할 이유를 미리 알려 주려는 것이지 인증이 목적이 아니다.
// 형식을 못 읽으면 만료로 단정하지 않는다 — 멀쩡한 토큰을 만료라 부르면 진단이 더 꼬인다.
func RegistrationTokenExpired(token string, now time.Time) bool {
	parts := strings.Split(token, ".")
	if len(parts) != 3 {
		return false
	}
	payload, err := base64.RawURLEncoding.DecodeString(parts[1])
	if err != nil {
		return false
	}
	var claims struct {
		Exp int64 `json:"exp"`
	}
	if err := json.Unmarshal(payload, &claims); err != nil || claims.Exp == 0 {
		return false
	}
	return now.After(time.Unix(claims.Exp, 0))
}
