// CountGpuNodes 회귀 테스트.
package k8s

import (
	"context"
	"testing"

	corev1 "k8s.io/api/core/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/api/resource"
)

func gpuNode(name string, gpuCount int64, withLabel bool) *corev1.Node {
	n := &corev1.Node{ObjectMeta: metav1.ObjectMeta{Name: name}}
	if gpuCount > 0 {
		n.Status.Capacity = corev1.ResourceList{
			"nvidia.com/gpu": *resource.NewQuantity(gpuCount, resource.DecimalSI),
		}
	}
	if withLabel {
		n.Labels = map[string]string{"nvidia.com/gpu.present": "true"}
	}
	return n
}

func plainNode(name string) *corev1.Node {
	return &corev1.Node{ObjectMeta: metav1.ObjectMeta{Name: name}}
}

func TestCountGpuNodes_CapacityBased(t *testing.T) {
	c := newFakeRealClient(
		gpuNode("gpu-1", 4, false),
		gpuNode("gpu-2", 8, false),
		plainNode("cpu-1"),
	)
	n, err := c.CountGpuNodes(context.Background())
	if err != nil {
		t.Fatalf("err: %v", err)
	}
	if n != 2 {
		t.Errorf("got %d, want 2", n)
	}
}

func TestCountGpuNodes_LabelBased(t *testing.T) {
	// capacity 는 없지만 label 만 있는 경우 — gpu operator 가 driver 설치 전 단계 등.
	c := newFakeRealClient(
		gpuNode("gpu-1", 0, true),
		plainNode("cpu-1"),
	)
	n, err := c.CountGpuNodes(context.Background())
	if err != nil {
		t.Fatalf("err: %v", err)
	}
	if n != 1 {
		t.Errorf("got %d, want 1", n)
	}
}

func TestCountGpuNodes_BothCapacityAndLabel_CountsOnce(t *testing.T) {
	c := newFakeRealClient(
		gpuNode("gpu-1", 4, true),     // 두 조건 모두 만족 — 1 회만 카운트.
	)
	n, err := c.CountGpuNodes(context.Background())
	if err != nil {
		t.Fatalf("err: %v", err)
	}
	if n != 1 {
		t.Errorf("got %d, want 1", n)
	}
}

func TestCountGpuNodes_AllCpuOnly_ReturnsZero(t *testing.T) {
	c := newFakeRealClient(
		plainNode("cpu-1"),
		plainNode("cpu-2"),
		plainNode("cpu-3"),
	)
	n, err := c.CountGpuNodes(context.Background())
	if err != nil {
		t.Fatalf("err: %v", err)
	}
	if n != 0 {
		t.Errorf("got %d, want 0", n)
	}
}

func TestCountGpuNodes_EmptyCluster(t *testing.T) {
	c := newFakeRealClient()
	n, err := c.CountGpuNodes(context.Background())
	if err != nil {
		t.Fatalf("err: %v", err)
	}
	if n != 0 {
		t.Errorf("got %d, want 0", n)
	}
}

func TestNodeHasGpu_ZeroCapacity_ReturnsFalse(t *testing.T) {
	// nvidia.com/gpu key 는 있지만 0 인 경우 — 드물지만 방어.
	n := &corev1.Node{
		Status: corev1.NodeStatus{
			Capacity: corev1.ResourceList{
				"nvidia.com/gpu": *resource.NewQuantity(0, resource.DecimalSI),
			},
		},
	}
	if nodeHasGpu(n) {
		t.Error("nodeHasGpu should be false when capacity is 0")
	}
}
