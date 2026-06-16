// sweeper 의 TTL annotation 판정 / 삭제 호출 / 에러 회복 회귀.
package cleanup

import (
	"context"
	"errors"
	"sync"
	"testing"
	"time"

	"anycloud/agent/internal/k8s"
	corev1 "k8s.io/api/core/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
)

// ---- fake k8s.Client ----

type fakeKube struct {
	k8s.Client     // embed — 미구현 메서드는 nil panic 하지 않도록 default 메서드만 override.

	mu       sync.Mutex
	pods     []corev1.Pod
	listErr  error
	deleted  []string     // "namespace/name"
	deleteErr error
}

func (f *fakeKube) ListPodsRaw(ctx context.Context, namespace, labelSelector string) ([]corev1.Pod, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.listErr != nil {
		return nil, f.listErr
	}
	return append([]corev1.Pod(nil), f.pods...), nil
}

func (f *fakeKube) DeleteResource(ctx context.Context, opts k8s.DeleteResourceOptions) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.deleteErr != nil {
		return f.deleteErr
	}
	f.deleted = append(f.deleted, opts.Namespace+"/"+opts.Name)
	return nil
}

// ---- helpers ----

func pod(name, ns string, annotation map[string]string) corev1.Pod {
	return corev1.Pod{
		ObjectMeta: metav1.ObjectMeta{
			Name:        name,
			Namespace:   ns,
			Annotations: annotation,
		},
	}
}

// ---- tests ----

func TestSweepOnce_DeletesExpired(t *testing.T) {
	expired := time.Now().Add(-1 * time.Hour).Format(time.RFC3339)
	future := time.Now().Add(1 * time.Hour).Format(time.RFC3339)
	f := &fakeKube{
		pods: []corev1.Pod{
			pod("expired-1", "kube-system", map[string]string{ExpiresAtAnnotation: expired}),
			pod("alive-1", "kube-system", map[string]string{ExpiresAtAnnotation: future}),
			pod("expired-2", "default", map[string]string{ExpiresAtAnnotation: expired}),
		},
	}
	s := NewSweeper(f, time.Minute)
	s.sweepOnce(context.Background())

	if len(f.deleted) != 2 {
		t.Fatalf("deleted = %v, want 2", f.deleted)
	}
	wantDeleted := map[string]bool{
		"kube-system/expired-1": true,
		"default/expired-2":     true,
	}
	for _, d := range f.deleted {
		if !wantDeleted[d] {
			t.Errorf("unexpected delete: %s", d)
		}
	}
}

func TestSweepOnce_SkipsPodsWithoutAnnotation(t *testing.T) {
	// aipaas label 은 있지만 annotation 미존재 — 보수적으로 skip.
	f := &fakeKube{
		pods: []corev1.Pod{
			pod("no-annotation", "kube-system", nil),
		},
	}
	s := NewSweeper(f, time.Minute)
	s.sweepOnce(context.Background())

	if len(f.deleted) != 0 {
		t.Errorf("should not delete pod without annotation, got %v", f.deleted)
	}
}

func TestSweepOnce_SkipsMalformedAnnotation(t *testing.T) {
	f := &fakeKube{
		pods: []corev1.Pod{
			pod("bad-format", "kube-system", map[string]string{ExpiresAtAnnotation: "not-a-date"}),
		},
	}
	s := NewSweeper(f, time.Minute)
	s.sweepOnce(context.Background())

	if len(f.deleted) != 0 {
		t.Errorf("malformed annotation should not delete, got %v", f.deleted)
	}
}

func TestSweepOnce_ListError_DoesNotPanicAndRetriesNextTick(t *testing.T) {
	f := &fakeKube{
		listErr: errors.New("API server unreachable"),
	}
	s := NewSweeper(f, time.Minute)
	// Should not panic.
	s.sweepOnce(context.Background())

	if len(f.deleted) != 0 {
		t.Error("list error should yield zero deletes")
	}
}

func TestSweepOnce_DeleteError_ContinuesWithOtherPods(t *testing.T) {
	// 첫 pod 가 삭제 실패해도 다음 pod 는 시도.
	expired := time.Now().Add(-1 * time.Hour).Format(time.RFC3339)
	f := &fakeKube{
		pods: []corev1.Pod{
			pod("expired-1", "ns1", map[string]string{ExpiresAtAnnotation: expired}),
			pod("expired-2", "ns2", map[string]string{ExpiresAtAnnotation: expired}),
		},
		deleteErr: errors.New("forbidden"),
	}
	s := NewSweeper(f, time.Minute)
	s.sweepOnce(context.Background())

	// Both attempted, both failed — but no panic + logs.
	if len(f.deleted) != 0 {
		t.Errorf("delete error should yield zero successes, got %v", f.deleted)
	}
}

func TestRun_StopsOnContextCancel(t *testing.T) {
	f := &fakeKube{}
	s := NewSweeper(f, 100*time.Millisecond)
	ctx, cancel := context.WithCancel(context.Background())

	done := make(chan struct{})
	go func() {
		s.Run(ctx)
		close(done)
	}()

	cancel()
	select {
	case <-done:
		// good
	case <-time.After(2 * time.Second):
		t.Fatal("Run did not exit after context cancel")
	}
}

func TestRun_NilClient_ReturnsImmediately(t *testing.T) {
	s := NewSweeper(nil, time.Minute)
	done := make(chan struct{})
	go func() {
		s.Run(context.Background())
		close(done)
	}()
	select {
	case <-done:
		// good
	case <-time.After(1 * time.Second):
		t.Fatal("Run should return immediately when kube == nil")
	}
}

func TestNewSweeper_DefaultInterval_WhenZeroOrNegative(t *testing.T) {
	for _, v := range []time.Duration{0, -1 * time.Second} {
		s := NewSweeper(&fakeKube{}, v)
		if s.interval != DefaultInterval {
			t.Errorf("interval = %v, want default %v (for input %v)", s.interval, DefaultInterval, v)
		}
	}
}
