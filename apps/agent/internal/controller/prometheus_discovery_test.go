package controller

import (
	"context"
	"encoding/json"
	"strings"
	"testing"
	"time"

	corev1 "k8s.io/api/core/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"

	"anycloud/agent/internal/k8s"
)

// 우리 카탈로그가 설치하는 kube-prometheus-stack 의 service 라벨 — 실제 클러스터에서 그대로 옮겨왔다.
// app.kubernetes.io/name 도 prometheus.io/scrape 도 없다.
func stackService() corev1.Service {
	return corev1.Service{
		ObjectMeta: metav1.ObjectMeta{
			Name:      "kube-prometheus-stack-prometheus",
			Namespace: "monitoring",
			Labels: map[string]string{
				"app":                          "kube-prometheus-stack-prometheus",
				"app.kubernetes.io/part-of":    "kube-prometheus-stack",
				"app.kubernetes.io/managed-by": "Helm",
			},
		},
		Spec: corev1.ServiceSpec{
			ClusterIP: "10.106.107.70",
			Ports:     []corev1.ServicePort{{Name: "http-web", Port: 9090}},
		},
	}
}

// 이름으로 찾을 때만 걸리는 fake — 라벨 셀렉터에는 아무것도 주지 않는다.
func dispatcherFindingByName(t *testing.T, calls *[]k8s.ListResourcesOptions) *Dispatcher {
	t.Helper()
	m := &mockK8sClient{}
	m.listResFn = func(_ context.Context, opts k8s.ListResourcesOptions) (*k8s.ListResourcesResult, error) {
		*calls = append(*calls, opts)
		if opts.LabelSelector != "" {
			return &k8s.ListResourcesResult{Items: `{"items":[]}`}, nil
		}
		body, _ := json.Marshal(map[string]any{"items": []corev1.Service{stackService()}})
		return &k8s.ListResourcesResult{Items: string(body)}, nil
	}
	return &Dispatcher{kube: m}
}

func resetPromCache() {
	promCacheMu.Lock()
	promCacheV = promCache{}
	promCacheMu.Unlock()
}

// 우리가 설치한 스택을 라벨로 못 찾아 매번 LIST 두 번을 버리고 있었다. 첫 조회가 5초씩 걸렸다.
func TestDiscoverPrometheus_FindsTheStackWeInstall(t *testing.T) {
	resetPromCache()
	var calls []k8s.ListResourcesOptions
	d := dispatcherFindingByName(t, &calls)

	url := discoverPrometheusURL(context.Background(), d, "monitoring")

	if !strings.Contains(url, "kube-prometheus-stack-prometheus.monitoring.svc:9090") {
		t.Fatalf("설치한 prometheus 를 찾지 못했다: %s", url)
	}
}

// 흔한 경우를 먼저 본다. 라벨 스캔이 앞에 있으면 매 갱신마다 헛도는 LIST 를 먼저 낸다.
func TestDiscoverPrometheus_LooksUpTheWellKnownNameFirst(t *testing.T) {
	resetPromCache()
	var calls []k8s.ListResourcesOptions
	d := dispatcherFindingByName(t, &calls)

	discoverPrometheusURL(context.Background(), d, "monitoring")

	if len(calls) == 0 {
		t.Fatal("조회가 없었다")
	}
	if calls[0].LabelSelector != "" {
		t.Fatalf("라벨 스캔이 먼저 나갔다: %q", calls[0].LabelSelector)
	}
}

// 직접 올린 prometheus 는 이름이 다르다. 이름으로 못 찾으면 라벨로 찾아야 한다.
func TestDiscoverPrometheus_FallsBackToLabels(t *testing.T) {
	resetPromCache()
	custom := stackService()
	custom.Name = "my-prom"
	custom.Labels = map[string]string{"app.kubernetes.io/name": "prometheus"}

	m := &mockK8sClient{}
	m.listResFn = func(_ context.Context, opts k8s.ListResourcesOptions) (*k8s.ListResourcesResult, error) {
		if opts.LabelSelector == "app.kubernetes.io/name=prometheus" {
			body, _ := json.Marshal(map[string]any{"items": []corev1.Service{custom}})
			return &k8s.ListResourcesResult{Items: string(body)}, nil
		}
		return &k8s.ListResourcesResult{Items: `{"items":[]}`}, nil
	}

	url := discoverPrometheusURL(context.Background(), &Dispatcher{kube: m}, "monitoring")

	if !strings.Contains(url, "my-prom.monitoring.svc:9090") {
		t.Fatalf("직접 올린 prometheus 를 못 찾았다: %s", url)
	}
}

// 아무것도 없는 클러스터에서 매 5분마다 조회를 반복하면, 모니터링 화면을 열 때마다 기다린다.
func TestDiscoverPrometheus_RemembersEvenWhenNothingIsFound(t *testing.T) {
	resetPromCache()
	var calls int
	m := &mockK8sClient{}
	m.listResFn = func(_ context.Context, _ k8s.ListResourcesOptions) (*k8s.ListResourcesResult, error) {
		calls++
		return &k8s.ListResourcesResult{Items: `{"items":[]}`}, nil
	}
	d := &Dispatcher{kube: m}

	first := discoverPrometheusURL(context.Background(), d, "monitoring")
	after := calls
	second := discoverPrometheusURL(context.Background(), d, "monitoring")

	if second != first {
		t.Fatalf("같은 답이 아니다: %s vs %s", first, second)
	}
	if calls != after {
		t.Fatalf("없는 것을 다시 찾았다: %d → %d", after, calls)
	}
}

func TestDiscoverPrometheus_UsesTheCacheWhileFresh(t *testing.T) {
	resetPromCache()
	promCacheMu.Lock()
	promCacheV = promCache{url: "http://cached:9090", expiresAt: time.Now().Add(time.Minute)}
	promCacheMu.Unlock()

	m := &mockK8sClient{}
	m.listResFn = func(_ context.Context, _ k8s.ListResourcesOptions) (*k8s.ListResourcesResult, error) {
		t.Fatal("캐시가 있는데 조회했다")
		return nil, nil
	}

	if got := discoverPrometheusURL(context.Background(), &Dispatcher{kube: m}, "monitoring"); got != "http://cached:9090" {
		t.Fatalf("캐시를 쓰지 않았다: %s", got)
	}
}
