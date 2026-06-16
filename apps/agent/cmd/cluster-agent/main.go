// ClusterAgent 진입점.
//
// 흐름:
//   1. env 로딩 (BACKEND_GRPC_ADDR / REGISTRATION_TOKEN / 클러스터 식별 정보)
//   2. core.Run() 호출 → Register RPC → identity_token 수령
//   3. identity_token 메모리 보관 (K8s Secret 영구 저장)
//   4. SIGTERM 까지 대기 — runtime stream 실행
package main

import (
	"context"
	"errors"
	"log/slog"
	"os"
	"os/signal"
	"syscall"
	"time"

	"anycloud/agent/internal/cleanup"
	"anycloud/agent/internal/config"
	"anycloud/agent/internal/controller"
	"anycloud/agent/internal/core"
	execpkg "anycloud/agent/internal/exec"
	logstreampkg "anycloud/agent/internal/logstream"
	"anycloud/agent/internal/tlsconfig"
	"anycloud/agent/internal/helm"
	"anycloud/agent/internal/k8s"
	"anycloud/agent/internal/leader"
	"k8s.io/client-go/kubernetes"
	"k8s.io/client-go/rest"
	"k8s.io/client-go/tools/clientcmd"
)

// AgentMode 는 같은 binary 가 어떤 역할로 동작할지 구분. helm split mode 와 짝.
//   - single    : 단일 pod 가 모든 RPC 처리 (default, dev/staging)
//   - core      : read-only RPC 만 처리 (split mode 의 -core Deployment)
//   - installer : mutating RPC 만 처리 (split mode 의 -installer Deployment)
// dispatcher 의 modeAllowsCommand 가 RPC level 에서 분기. const 는 controller package
// 의 단일 source 사용 — controller.ModeSingle / ModeCore / ModeInstaller.

// buildVersionStr — ldflags 로 주입. 기본값은 "dev".
var buildVersionStr = "dev"

func main() {
	logger := slog.New(slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{Level: slog.LevelInfo}))
	slog.SetDefault(logger)

	mode := envOr("AGENT_MODE", controller.ModeSingle)
	backendAddr := envOr("BACKEND_GRPC_ADDR", "localhost:9090")
	registrationToken := os.Getenv("REGISTRATION_TOKEN")
	agentInstanceID := envOr("AGENT_INSTANCE_ID", generateInstanceID())

	slog.Info("cluster-agent starting",
		slog.String("mode", mode),
		slog.String("backend", backendAddr),
		slog.String("agent_instance_id", agentInstanceID),
		slog.String("version", buildVersionStr))

	// single / core / installer 모두 정상 startup. dispatcher 의 modeAllowsCommand 가 RPC level
	// 에서 분기 처리. 알 수 없는 mode 는 conservative 하게 single 처럼 동작 (forward-compat).
	if mode != controller.ModeCore && mode != controller.ModeInstaller && mode != controller.ModeSingle {
		slog.Warn("unknown AGENT_MODE — treating as single", slog.String("mode", mode))
		mode = controller.ModeSingle
	}

	// REGISTRATION_TOKEN validation 은 아래 LoadIdentityOrBootstrap 직전에서 — Secret 에서
	// 영구 token 로드에 성공하면 env 가 비어 있어도 Register 자체를 건너뛰므로 OK. Secret 미존재
	// + env 비어 있는 경우만 명시 에러 (Os.Exit(3)).

	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGTERM, syscall.SIGINT)
	defer stop()

	// K8s client 초기화 — in-cluster (Pod 안) 또는 KUBECONFIG fallback. 실패 시 Dispatcher
	// 가 nil 받지만 K8s 명령은 AGENT_UNAVAILABLE 반환 (graceful degradation).
	kubeClient, kubeErr := k8s.NewClient()
	if kubeErr != nil {
		slog.Warn("k8s client init failed — K8s commands will return AGENT_UNAVAILABLE",
			slog.String("error", kubeErr.Error()))
	}

	// Bootstrap 시 cluster info 자동 수집 (UID / version / distribution). K8s client 가 없으면
	// env fallback. K8S_CLUSTER_UID env 가 있으면 그것 우선 (테스트 / 명시적 override).
	clusterUID := os.Getenv("K8S_CLUSTER_UID")
	k8sVersion := os.Getenv("K8S_VERSION")
	distribution := os.Getenv("K8S_DISTRIBUTION")
	apiEndpoint := os.Getenv("K8S_API_ENDPOINT")
	var serverCA string
	if kubeClient != nil && (clusterUID == "" || k8sVersion == "") {
		discoverCtx, discoverCancel := context.WithTimeout(ctx, 5*time.Second)
		if info, err := kubeClient.ClusterInfo(discoverCtx); err == nil {
			if clusterUID == "" {
				clusterUID = info.K8sClusterUID
			}
			if k8sVersion == "" {
				k8sVersion = info.Version
			}
			if distribution == "" {
				distribution = info.Distribution
			}
			if apiEndpoint == "" {
				apiEndpoint = info.APIServerEndpoint
			}
			slog.Info("k8s info auto-discovered",
				slog.String("uid", clusterUID),
				slog.String("version", k8sVersion),
				slog.String("distribution", distribution))
		} else {
			slog.Warn("k8s info auto-discover failed", slog.String("error", err.Error()))
		}
		discoverCancel()
	}
	// Server CA — agent-first 등록 경로 (status=AGENT_PENDING) 의 ClusterEntity backfill 에 필요.
	// kubeClient 가 없거나 CA 를 못 가져오면 비워서 보냄 (backend 가 알아서 skip).
	if kubeClient != nil {
		if caBytes, err := kubeClient.ServerCAData(); err == nil && len(caBytes) > 0 {
			serverCA = string(caBytes)
		} else if err != nil {
			slog.Warn("server CA fetch failed — backfill skipped", slog.String("error", err.Error()))
		}
	}

	// TLS config — env 에서 한 번 로드해서 모든 5 dial site (bootstrap/rotation/runtime/exec/logstream)
	// 에 동일하게 전달.
	tlsCfg := tlsconfig.FromEnv()
	if tlsCfg.Enabled {
		slog.Info("backend gRPC TLS enabled",
			slog.String("ca_path", tlsCfg.CACertPath),
			slog.Bool("ca_inline", tlsCfg.CACertPEM != ""),
			slog.String("server_name", tlsCfg.ServerName),
			slog.Bool("insecure_skip_verify", tlsCfg.InsecureSkipVerify))
		if tlsCfg.InsecureSkipVerify {
			slog.Warn("BACKEND_TLS_INSECURE_SKIP_VERIFY=true — cert verification disabled (DEV ONLY)")
		}
	} else {
		slog.Info("backend gRPC plaintext (TLS disabled — set BACKEND_GRPC_TLS_ENABLED=true to enable)")
	}

	cfg := core.BootstrapConfig{
		BackendAddr:        backendAddr,
		RegistrationToken:  registrationToken,
		KubernetesUID:      clusterUID,
		KubernetesVersion:  envOr2(k8sVersion, "unknown"),
		Distribution:       envOr2(distribution, "kubeadm"),
		APIServerEndpoint:  apiEndpoint,
		ServerCA:           serverCA,
		AgentInstanceID:    agentInstanceID,
		AgentVersion:       buildVersionStr,
		PodName:            os.Getenv("POD_NAME"),
		PublicIP:           os.Getenv("PUBLIC_IP"),
		PrivateIP:          os.Getenv("PRIVATE_IP"),
		DialTimeout:        10 * time.Second,
		RegisterTimeout:    30 * time.Second,
		TLS:                tlsCfg,
	}

	// Identity store — Secret 에 영구 보관된 60일 opaque token. k8s client 가 없으면 nil
	// (메모리 only — pod restart 시 re-register 필요). mTLS 폐기 후 bearer 단일 인증.
	var identityStore core.IdentityStore
	if kubeClient != nil {
		identityStore = core.NewK8sSecretIdentityStore(kubeClient.Clientset(),
			os.Getenv("AGENT_NAMESPACE"), os.Getenv("AGENT_IDENTITY_SECRET_NAME"))
	}

	// REGISTRATION_TOKEN 검증 완화: Secret 에서 valid token 가 로드되면 env 안 필요.
	// 검증 순서: 먼저 store.Load 시도 → 실패/만료 시 env 검증 → Register 진행.
	if registrationToken == "" && identityStore != nil {
		if existing, err := identityStore.Load(ctx); err == nil &&
			existing.IsValid(time.Now(), 5*time.Minute) {
			slog.Info("identity token present in secret — REGISTRATION_TOKEN env not required")
		} else {
			slog.Error("REGISTRATION_TOKEN env required (no valid identity secret) — exiting. " +
				"Get token from: POST /v1/clusters/{clusterId}/agent-registration")
			os.Exit(3)
		}
	} else if registrationToken == "" {
		// k8s client 없음 + env 도 없음 — bootstrap 불가.
		slog.Error("REGISTRATION_TOKEN env required — exiting. " +
			"Get token from: POST /v1/clusters/{clusterId}/agent-registration")
		os.Exit(3)
	}

	// Bootstrap — identity_token 이 store 에 valid 면 Register skip, 아니면 backend 에 Register.
	result, err := core.BootstrapIdentity(ctx, cfg, identityStore, 5*time.Minute)
	if err != nil {
		slog.Error("bootstrap failed", slog.String("error", err.Error()))
		os.Exit(4)
	}

	slog.Info("bootstrap success — opening runtime stream",
		slog.String("cluster_id", result.ClusterID),
		slog.Int("identity_token_length", len(result.AgentIdentityToken)))

	dialTlsCfg := tlsCfg

	// runLeaderWork — leader 가 되었을 때 (또는 leader election off 일 때) 시작하는 핵심 작업.
	// backend stream, rotation timer, debug pod sweeper 모두 leader 만 실행.
	runLeaderWork := func(leaderCtx context.Context) {
		helmClient := initHelmClient()
		allowlistLoader := initAllowList(leaderCtx)

		// Agent OIDC binding reconciler 폐기. RBAC starter (backend) 가 K8s API 로 직접
		// ClusterRoleBinding apply. apply_config 의 oidc_bindings field 는 ConfigMap 에 저장만.
		dispatcher := controller.New(agentInstanceID, mode, kubeClient, helmClient, allowlistLoader)
		runtimeCfg := core.DefaultRuntimeConfig()
		runtimeCfg.BackendAddr = backendAddr
		runtimeCfg.AgentIdentityToken = result.AgentIdentityToken
		runtimeCfg.AgentInstanceID = agentInstanceID
		runtimeCfg.TLS = dialTlsCfg

		exp, _ := time.Parse(time.RFC3339, result.ExpiresAt)
		tokenStore := core.NewTokenStore(result.AgentIdentityToken, exp)
		runtimeCfg.TokenStore = tokenStore

		rotationCfg := core.DefaultRotationConfig()
		rotationCfg.BackendAddr = backendAddr
		rotationCfg.AgentInstanceID = agentInstanceID
		rotationCfg.TLS = dialTlsCfg
		go core.RunRotation(leaderCtx, rotationCfg, tokenStore, identityStore, result.ClusterID,
				func(newToken string, expiresAt time.Time) {
			slog.Info("identity token rotated — stream will force-reconnect with new token",
					slog.Time("new_expires_at", expiresAt))
		})

		// identity_token 의 rotation 만으로 인증 갱신 (RunRotation, 위에 wired).

		var execRunner *execpkg.Runner
		var logRunner *logstreampkg.Runner
		if kubeClient != nil {
			execRunner = execpkg.New(kubeClient, allowlistLoader)
			logRunner = logstreampkg.New(kubeClient, allowlistLoader)
		}

		go cleanup.NewSweeper(kubeClient, cleanup.DefaultInterval).Run(leaderCtx)

		if err := core.RunStream(leaderCtx, runtimeCfg, dispatcher, execRunner, logRunner, kubeClient); err != nil && !errors.Is(err, context.Canceled) {
			slog.Error("runtime stream terminated", slog.String("error", err.Error()))
			// leader 모드: stream 종료해도 leader 잃기 전엔 retry. non-leader 모드 (legacy) 면 exit.
			if envOr("AGENT_LEADER_ELECTION", "false") != "true" {
				os.Exit(5)
			}
		}
	}

	// AGENT_LEADER_ELECTION=true 시 K8s Lease 로 leader 1개 선출. non-leader 는 idle 대기.
	// default false — 기존 동작 (모든 replica 가 backend 연결).
	if envOr("AGENT_LEADER_ELECTION", "false") == "true" {
		clientset := buildK8sClientset()
		if clientset == nil {
			slog.Error("leader election requested but K8s clientset unavailable — fallback to single-leader mode")
			runLeaderWork(ctx)
			return
		}
		leaseNamespace := envOr("AGENT_NAMESPACE", "aipaas-system")
		err := leader.Run(ctx, leader.Options{
			Namespace: leaseNamespace,
			LeaseName: envOr("AGENT_LEASE_NAME", "aipaas-agent-leader"),
			Identity:  envOr("POD_NAME", agentInstanceID),
			Clientset: clientset,
			OnStartedLeading: func(leaderCtx context.Context) {
				runLeaderWork(leaderCtx)
			},
			OnStoppedLeading: func() {
				slog.Info("leader lost — backend stream + workers stopped (will retry on re-election)")
			},
		})
		if err != nil && !errors.Is(err, context.Canceled) {
			slog.Error("leader election terminated", slog.String("error", err.Error()))
			os.Exit(6)
		}
	} else {
		runLeaderWork(ctx)
	}
	slog.Info("agent shutdown complete")
}

func envOr(key, fallback string) string {
	if v, ok := os.LookupEnv(key); ok && v != "" {
		return v
	}
	return fallback
}

func envOr2(value, fallback string) string {
	if value != "" {
		return value
	}
	return fallback
}

// generateInstanceID — Pod 마다 unique. K8s downward API 의 pod UID 사용 권장.
// 현재는 PID + boot time 으로 간단 생성.
func generateInstanceID() string {
	return time.Now().Format("20060102T150405") + "-" + envOr("HOSTNAME", "local")
}

// initHelmClient — in-cluster restConfig 또는 KUBECONFIG fallback. 실패 시 nil 반환 (Helm
// 명령은 AGENT_UNAVAILABLE 로 graceful degradation).
func initHelmClient() helm.Client {
	if _, err := rest.InClusterConfig(); err == nil {
		// In-cluster: helm 의 cli.New() 가 자동으로 in-cluster 인식 (KUBECONFIG 없으면).
		return helm.NewClientFromKubeFlags("")
	}
	kubeconfig := os.Getenv("KUBECONFIG")
	if kubeconfig == "" {
		// out-of-cluster but no KUBECONFIG — helm settings 가 ~/.kube/config 자동 시도.
		kubeconfig = clientcmd.RecommendedHomeFile
	}
	return helm.NewClientFromKubeFlags(kubeconfig)
}

// initAllowList — kube-system 의 ServiceAccount 권한으로 aipaas-system/aipaas-agent-allowlist
// ConfigMap 을 watch. ConfigMap 없으면 deny-all 유지. Watch 는 background goroutine.
func initAllowList(ctx context.Context) *config.Loader {
	cs := buildK8sClientset()
	if cs == nil {
		slog.Warn("allowlist: K8s clientset unavailable — deny-all default")
		return nil
	}
	namespace := envOr("AGENT_NAMESPACE", "aipaas-system")
	cmName := envOr("ALLOWLIST_CONFIGMAP", "aipaas-agent-allowlist")
	loader := config.NewLoader(cs, namespace, cmName)

	loadCtx, loadCancel := context.WithTimeout(ctx, 5*time.Second)
	if err := loader.LoadOnce(loadCtx); err != nil {
		slog.Warn("allowlist: initial load failed — deny-all until ConfigMap appears",
			slog.String("error", err.Error()))
	}
	loadCancel()

	go func() {
		for {
			if err := loader.Watch(ctx); err != nil && !errors.Is(err, context.Canceled) {
				slog.Warn("allowlist: watch ended — retry in 10s", slog.String("error", err.Error()))
				select {
				case <-ctx.Done():
					return
				case <-time.After(10 * time.Second):
				}
				continue
			}
			return
		}
	}()
	return loader
}

// ConfigMap watch (config.Loader) 가 단일 reload path.
// 운영자의 kubectl edit / helm upgrade / Argo CD 모두 ConfigMap 변경 → Loader 가 감지 → 정책 reload.

func buildK8sClientset() kubernetes.Interface {
	if cfg, err := rest.InClusterConfig(); err == nil {
		if cs, err := kubernetes.NewForConfig(cfg); err == nil {
			return cs
		}
	}
	// Out-of-cluster fallback.
	path := os.Getenv("KUBECONFIG")
	if path == "" {
		path = clientcmd.RecommendedHomeFile
	}
	cfg, err := clientcmd.BuildConfigFromFlags("", path)
	if err != nil {
		return nil
	}
	cs, err := kubernetes.NewForConfig(cfg)
	if err != nil {
		return nil
	}
	return cs
}
