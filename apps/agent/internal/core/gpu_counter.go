// cluster GPU 노드 수 캐싱.
//
// Heartbeat 마다 K8s API 호출하면 cluster 부하 — 5분 TTL 캐시로 완화. atomic.Int32 로 lock-free.
//
// Refresh 전략:
//   - 첫 호출 (캐시 비어있음) → blocking refresh
//   - 이후 호출 → 캐시 값 반환 + 백그라운드 refresh trigger (캐시가 TTL 지났을 때만)
//
// 에러 발생 시 마지막 성공값을 계속 반환 — heartbeat 가 stale 값을 보낸들 backend 가 update 결정만
// 늦어질 뿐 안전. 첫 호출 실패면 0 반환.
package core

import (
	"context"
	"log/slog"
	"sync"
	"sync/atomic"
	"time"

	"anycloud/agent/internal/k8s"
)

const defaultGpuCacheTTL = 5 * time.Minute

// gpuNodeCounter — thread-safe cached counter.
type gpuNodeCounter struct {
	kube k8s.Client
	ttl  time.Duration

	count       atomic.Int32
	lastFetched atomic.Int64     // unix nano
	initOnce    sync.Once

	// 진행 중인 refresh 가 1 개만 — 중복 K8s 호출 방지.
	refreshing atomic.Bool
}

func newGpuNodeCounter(kube k8s.Client) *gpuNodeCounter {
	return &gpuNodeCounter{kube: kube, ttl: defaultGpuCacheTTL}
}

// get — 캐시 값 반환. 만료됐으면 백그라운드 refresh 시작 (다음 호출 때 새 값).
// kube == nil 이면 항상 0 반환 (k8s client 미가용 — 다른 명령들도 AGENT_UNAVAILABLE 상태).
func (g *gpuNodeCounter) get(ctx context.Context) int32 {
	if g == nil || g.kube == nil {
		return 0
	}

	// 첫 호출 — blocking refresh 로 캐시 초기화.
	g.initOnce.Do(func() {
		g.refresh(ctx)
	})

	// 캐시 만료 시 백그라운드 refresh (다음 heartbeat 가 새 값 사용).
	last := g.lastFetched.Load()
	if time.Since(time.Unix(0, last)) > g.ttl {
		// 동시 refresh 1 개만.
		if g.refreshing.CompareAndSwap(false, true) {
			go func() {
				defer g.refreshing.Store(false)
				bgCtx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
				defer cancel()
				g.refresh(bgCtx)
			}()
		}
	}
	return g.count.Load()
}

func (g *gpuNodeCounter) refresh(ctx context.Context) {
	n, err := g.kube.CountGpuNodes(ctx)
	if err != nil {
		slog.Debug("gpuNodeCounter: CountGpuNodes failed (keeping cached value)",
			slog.String("error", err.Error()))
		// 실패 시 lastFetched 만 갱신 안 해서 다음 호출이 다시 refresh 시도.
		return
	}
	g.count.Store(int32(n))
	g.lastFetched.Store(time.Now().UnixNano())
	slog.Debug("gpuNodeCounter: refreshed", slog.Int("gpu_node_count", n))
}
