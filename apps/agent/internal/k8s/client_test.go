package k8s

import (
	"context"
	"testing"

	corev1 "k8s.io/api/core/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/runtime"
	"k8s.io/apimachinery/pkg/types"
	"k8s.io/apimachinery/pkg/version"
	"k8s.io/client-go/kubernetes/fake"
	fakediscovery "k8s.io/client-go/discovery/fake"
	"k8s.io/client-go/rest"
)

// newFakeRealClient — fake clientset 으로 realClient 를 만든다. Pod list / cluster info 검증용.
// GetPodLogs 는 fake clientset 의 Stream() 이 SubResource 라 mock 안 되므로 본 테스트에서 제외.
func newFakeRealClient(objects ...runtime.Object) *realClient {
	cs := fake.NewSimpleClientset(objects...)
	// Server version 도 stub.
	if fd, ok := cs.Discovery().(*fakediscovery.FakeDiscovery); ok {
		fd.FakedServerVersion = &version.Info{GitVersion: "v1.34.3"}
	}
	return &realClient{cs: cs, restConfig: &rest.Config{Host: "https://fake:6443"}}
}

func TestRealClient_ListPods(t *testing.T) {
	pod := &corev1.Pod{
		ObjectMeta: metav1.ObjectMeta{Name: "nginx-1", Namespace: "web"},
		Status: corev1.PodStatus{
			Phase: corev1.PodRunning,
			PodIP: "10.0.0.5",
			ContainerStatuses: []corev1.ContainerStatus{{Ready: true}, {Ready: false}},
		},
		Spec: corev1.PodSpec{NodeName: "node-1"},
	}
	c := newFakeRealClient(pod)

	pods, err := c.ListPods(context.Background(), "web")
	if err != nil {
		t.Fatalf("ListPods: %v", err)
	}
	if len(pods) != 1 {
		t.Fatalf("len = %d, want 1", len(pods))
	}
	if pods[0].Name != "nginx-1" {
		t.Errorf("name = %q", pods[0].Name)
	}
	if pods[0].Phase != "Running" {
		t.Errorf("phase = %q", pods[0].Phase)
	}
	if pods[0].ContainersReady != 1 || pods[0].ContainersTotal != 2 {
		t.Errorf("ready/total = %d/%d, want 1/2", pods[0].ContainersReady, pods[0].ContainersTotal)
	}
}

func TestRealClient_ListPods_AllNamespaces(t *testing.T) {
	pods := []runtime.Object{
		&corev1.Pod{ObjectMeta: metav1.ObjectMeta{Name: "a", Namespace: "web"}},
		&corev1.Pod{ObjectMeta: metav1.ObjectMeta{Name: "b", Namespace: "api"}},
	}
	c := newFakeRealClient(pods...)

	got, err := c.ListPods(context.Background(), "")     // all-namespaces.
	if err != nil {
		t.Fatalf("ListPods: %v", err)
	}
	if len(got) != 2 {
		t.Errorf("len = %d, want 2", len(got))
	}
}

func TestRealClient_ClusterInfo(t *testing.T) {
	ns := &corev1.Namespace{
		ObjectMeta: metav1.ObjectMeta{
			Name: "kube-system",
			UID:  types.UID("550e8400-e29b-41d4-a716-446655440000"),
		},
	}
	node := &corev1.Node{ObjectMeta: metav1.ObjectMeta{Name: "node-1"}}
	c := newFakeRealClient(ns, node)

	info, err := c.ClusterInfo(context.Background())
	if err != nil {
		t.Fatalf("ClusterInfo: %v", err)
	}
	if info.K8sClusterUID != "550e8400-e29b-41d4-a716-446655440000" {
		t.Errorf("uid = %q", info.K8sClusterUID)
	}
	if info.Version != "v1.34.3" {
		t.Errorf("version = %q", info.Version)
	}
	if info.NodeCount != 1 {
		t.Errorf("node_count = %d, want 1", info.NodeCount)
	}
	if info.Distribution != "kubeadm" {
		t.Errorf("distribution = %q, want kubeadm", info.Distribution)
	}
}

func TestInferDistribution(t *testing.T) {
	cases := map[string]string{
		"v1.34.3":              "kubeadm",
		"v1.34.3-eks-12345":    "eks",
		"v1.34.3-gke.100":      "gke",
		"v1.34.3+k3s1":         "k3s",
	}
	for input, want := range cases {
		if got := inferDistribution(input); got != want {
			t.Errorf("inferDistribution(%q) = %q, want %q", input, got, want)
		}
	}
}
