// Package cleanup — TTL annotation 기반 ephemeral resource sweeper.
//
// debug pod 같이 short-lived 자원이 잊혀지지 않도록 자기 cluster 안에서 주기적으로 만료된 pod 자동
// 삭제 — 운영자 개입 0회. annotation parse 실패는 skip + log (보수적: 신뢰할 수 없으면 안 지움).
//
// 보호:
//   - 권한은 agent SA 가 이미 보유한 pod list/delete 만 사용 (RBAC 추가 X).
//   - label selector `app.kubernetes.io/managed-by=aipaas-cluster-agent` 로 본 agent 가 만든
//     자원만 — 다른 워크로드 영향 X.

package cleanup

import (
	"context"
	"log/slog"
	"time"

	"anycloud/agent/internal/k8s"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
)

const (
	// ManagedByLabel — CreateNodeDebugPod 가 부여하는 label 과 매칭.
	ManagedByLabel = "app.kubernetes.io/managed-by=aipaas-cluster-agent"

	// ExpiresAtAnnotation — 만료 시각 (RFC3339). 미존재 = skip.
	ExpiresAtAnnotation = "aipaas.io/expires-at"

	// DefaultInterval — 5 분 주기. cleanup 지연이 보안 critical 은 아님 — TTL 안에서 약간 늦는 OK.
	DefaultInterval = 5 * time.Minute
)

// Sweeper — 주기적으로 만료된 pod 삭제. K8s client 만 의존 (backend 와 무관, agent 단독 동작).
type Sweeper struct {
	kube     k8s.Client
	interval time.Duration
}

func NewSweeper(kube k8s.Client, interval time.Duration) *Sweeper {
	if interval <= 0 {
		interval = DefaultInterval
	}
	return &Sweeper{kube: kube, interval: interval}
}

// Run — context 종료까지 주기 sweep. ctx.Done() 시 return.
// kube == nil 이면 즉시 return (k8s 미가용 cluster — 다른 명령들도 AGENT_UNAVAILABLE).
func (s *Sweeper) Run(ctx context.Context) {
	if s.kube == nil {
		slog.Info("debug pod sweeper disabled: k8s client unavailable")
		return
	}
	slog.Info("debug pod sweeper started", slog.Duration("interval", s.interval))
	// 첫 sweep 은 즉시 (start-up 직후 cluster 에 잔존 pod cleanup).
	s.sweepOnce(ctx)

	ticker := time.NewTicker(s.interval)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			slog.Info("debug pod sweeper stopped")
			return
		case <-ticker.C:
			s.sweepOnce(ctx)
		}
	}
}

// sweepOnce — 1 회 sweep 수행. 결과 통계는 log 로만.
func (s *Sweeper) sweepOnce(ctx context.Context) {
	listCtx, cancel := context.WithTimeout(ctx, 30*time.Second)
	defer cancel()

	pods, err := s.kube.ListPodsRaw(listCtx, "", ManagedByLabel)     // all namespaces
	if err != nil {
		slog.Warn("debug pod sweeper: list failed (will retry next tick)",
			slog.String("error", err.Error()))
		return
	}

	now := time.Now()
	deleted := 0
	skipped := 0
	for _, pod := range pods {
		expStr, ok := pod.Annotations[ExpiresAtAnnotation]
		if !ok {
			// annotation 미존재 — 보수적으로 skip (다른 path 로 생성된 aipaas-managed pod 보호).
			continue
		}
		exp, err := time.Parse(time.RFC3339, expStr)
		if err != nil {
			slog.Debug("sweeper: malformed expires-at annotation, skipping",
				slog.String("pod", pod.Namespace+"/"+pod.Name),
				slog.String("value", expStr))
			skipped++
			continue
		}
		if exp.After(now) {
			// 아직 유효.
			continue
		}
		// 삭제 시도.
		if err := s.deletePod(ctx, pod.Namespace, pod.Name); err != nil {
			slog.Warn("sweeper: delete failed",
				slog.String("pod", pod.Namespace+"/"+pod.Name),
				slog.String("error", err.Error()))
			continue
		}
		deleted++
		slog.Info("sweeper: deleted expired pod",
			slog.String("pod", pod.Namespace+"/"+pod.Name),
			slog.String("expired_at", expStr))
	}
	if deleted > 0 || skipped > 0 {
		slog.Info("sweeper: sweep finished",
			slog.Int("deleted", deleted),
			slog.Int("malformed_skipped", skipped),
			slog.Int("total_seen", len(pods)))
	}
}

// deletePod — agent 의 기존 DeleteResource 재사용. zero-grace 로 즉시 삭제 (사용자 shell 이
// 이미 만료된 debug pod 라 graceful drain 불필요).
func (s *Sweeper) deletePod(ctx context.Context, namespace, name string) error {
	deleteCtx, cancel := context.WithTimeout(ctx, 30*time.Second)
	defer cancel()

	zero := int64(0)
	return s.kube.DeleteResource(deleteCtx, k8s.DeleteResourceOptions{
		Kind:               "pod",
		Namespace:          namespace,
		Name:               name,
		GracePeriodSeconds: &zero,
	})
}

// _ — metav1 unused import 회피 (test 에서 ObjectMeta 작성 위해 노출).
var _ = metav1.ObjectMeta{}
