// Package leader — cluster-side leader election (K8s Lease).
//
// Agent Deployment.replicas=N HA 구성에서 backend stream 은 leader 1 개만 유지. Non-leader 는
// idle — 명령 중복 처리 방지 + backend 부하 ↓. lease holder identity = POD_NAME (또는 hostname).
//
// Renewal: lease 15s / renew 10s / retry 2s — k8s 표준 권장값. control-plane 장애 시 1 renew
// cycle 안에 새 leader 등극.

package leader

import (
	"context"
	"fmt"
	"log/slog"
	"os"
	"time"

	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/client-go/kubernetes"
	"k8s.io/client-go/tools/leaderelection"
	"k8s.io/client-go/tools/leaderelection/resourcelock"
)

// Options — caller 가 채워 Run 에 전달.
type Options struct {
	// K8s 측 Lease 가 살 namespace + name. 매니페스트 생성 시 leader RBAC 도 같이 부여 필요.
	Namespace string
	LeaseName string

	// Lease holder identity — pod 별 unique. 미설정 시 POD_NAME env 또는 hostname 으로 fallback.
	Identity string

	// K8s clientset — agent 가 in-cluster 에서 이미 보유한 것.
	Clientset kubernetes.Interface

	// Callbacks.
	OnStartedLeading func(ctx context.Context)     // leader 획득 → 시작
	OnStoppedLeading func()                         // leader 잃음 → 정리
	OnNewLeader      func(leader string)            // 정보 — 다른 leader 등극

	// Optional — default 15s / 10s / 2s 권장값.
	LeaseDuration time.Duration
	RenewDeadline time.Duration
	RetryPeriod   time.Duration
}

// Run — context 종료까지 leader election 유지. blocking call (caller 는 별도 goroutine 권장).
//
// ctx.Done() 또는 leader 의 RenewDeadline 미달 시 자연스럽게 종료.
func Run(ctx context.Context, opts Options) error {
	if opts.Clientset == nil {
		return fmt.Errorf("leader.Run: Clientset required")
	}
	if opts.Namespace == "" {
		return fmt.Errorf("leader.Run: Namespace required")
	}
	if opts.LeaseName == "" {
		opts.LeaseName = "aipaas-agent-leader"
	}
	identity := opts.Identity
	if identity == "" {
		identity = os.Getenv("POD_NAME")
		if identity == "" {
			h, _ := os.Hostname()
			identity = h
		}
	}
	if opts.LeaseDuration <= 0 {
		opts.LeaseDuration = 15 * time.Second
	}
	if opts.RenewDeadline <= 0 {
		opts.RenewDeadline = 10 * time.Second
	}
	if opts.RetryPeriod <= 0 {
		opts.RetryPeriod = 2 * time.Second
	}

	lock := &resourcelock.LeaseLock{
		LeaseMeta: metav1.ObjectMeta{
			Name:      opts.LeaseName,
			Namespace: opts.Namespace,
		},
		Client: opts.Clientset.CoordinationV1(),
		LockConfig: resourcelock.ResourceLockConfig{
			Identity: identity,
		},
	}

	slog.Info("leader election starting",
		slog.String("namespace", opts.Namespace),
		slog.String("lease", opts.LeaseName),
		slog.String("identity", identity),
		slog.Duration("lease_duration", opts.LeaseDuration))

	leaderelection.RunOrDie(ctx, leaderelection.LeaderElectionConfig{
		Lock:            lock,
		LeaseDuration:   opts.LeaseDuration,
		RenewDeadline:   opts.RenewDeadline,
		RetryPeriod:     opts.RetryPeriod,
		ReleaseOnCancel: true,     // ctx cancel 시 lease 즉시 release — 다른 replica 가 빠르게 take.
		Callbacks: leaderelection.LeaderCallbacks{
			OnStartedLeading: func(c context.Context) {
				slog.Info("leader election: STARTED LEADING", slog.String("identity", identity))
				if opts.OnStartedLeading != nil {
					opts.OnStartedLeading(c)
				}
			},
			OnStoppedLeading: func() {
				slog.Info("leader election: STOPPED LEADING", slog.String("identity", identity))
				if opts.OnStoppedLeading != nil {
					opts.OnStoppedLeading()
				}
			},
			OnNewLeader: func(other string) {
				if other == identity {
					return     // 자기 자신 — OnStartedLeading 에서 이미 처리.
				}
				slog.Info("leader election: new leader observed",
					slog.String("new_leader", other),
					slog.String("self", identity))
				if opts.OnNewLeader != nil {
					opts.OnNewLeader(other)
				}
			},
		},
	})
	return ctx.Err()
}
