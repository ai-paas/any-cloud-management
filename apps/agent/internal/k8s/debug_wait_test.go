package k8s

import (
	"context"
	"testing"
	"time"

	corev1 "k8s.io/api/core/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/client-go/kubernetes/fake"
)

// 파드 이름만 반환하면 호출자가 즉시 exec 을 실행해 container not found 로 종료.
// 노드에 이미지가 없으면 pull 에 1분 이상 소요돼 대부분 해당.

func podWith(state corev1.ContainerState) *corev1.Pod {
	return &corev1.Pod{
		ObjectMeta: metav1.ObjectMeta{Name: "dbg", Namespace: "kube-system"},
		Status: corev1.PodStatus{
			Phase:             corev1.PodPending,
			ContainerStatuses: []corev1.ContainerStatus{{Name: debugContainerName, State: state}},
		},
	}
}

func TestRunningContainerReturnsImmediately(t *testing.T) {
	c := &realClient{cs: fake.NewSimpleClientset(podWith(corev1.ContainerState{
		Running: &corev1.ContainerStateRunning{},
	}))}

	if err := c.waitDebugContainerRunning(context.Background(), "kube-system", "dbg"); err != nil {
		t.Fatalf("준비된 컨테이너인데 오류: %v", err)
	}
}

func TestImagePullFailureIsReportedInsteadOfWaitingOut(t *testing.T) {
	// 대기로 해소되지 않는 상태. 상한까지 점유하면 사용자가 원인 없이 3분 대기.
	c := &realClient{cs: fake.NewSimpleClientset(podWith(corev1.ContainerState{
		Waiting: &corev1.ContainerStateWaiting{Reason: "ImagePullBackOff", Message: "manifest unknown"},
	}))}

	err := c.waitDebugContainerRunning(context.Background(), "kube-system", "dbg")
	if err == nil {
		t.Fatal("ImagePullBackOff 인데 통과했다")
	}
	if !contains(err.Error(), "ImagePullBackOff") || !contains(err.Error(), "manifest unknown") {
		t.Fatalf("원인이 메시지에 없다: %v", err)
	}
}

func TestExitedContainerIsReported(t *testing.T) {
	c := &realClient{cs: fake.NewSimpleClientset(podWith(corev1.ContainerState{
		Terminated: &corev1.ContainerStateTerminated{Reason: "Error"},
	}))}

	if err := c.waitDebugContainerRunning(context.Background(), "kube-system", "dbg"); err == nil {
		t.Fatal("종료된 컨테이너인데 통과했다")
	}
}

func TestPendingContainerWaitsUntilTheDeadline(t *testing.T) {
	c := &realClient{cs: fake.NewSimpleClientset(podWith(corev1.ContainerState{
		Waiting: &corev1.ContainerStateWaiting{Reason: "ContainerCreating"},
	}))}
	ctx, cancel := context.WithTimeout(context.Background(), 1500*time.Millisecond)
	defer cancel()

	err := c.waitDebugContainerRunning(ctx, "kube-system", "dbg")
	if err == nil {
		t.Fatal("준비되지 않았는데 통과했다")
	}
	if !contains(err.Error(), "ContainerCreating") {
		t.Fatalf("마지막 상태가 메시지에 없다: %v", err)
	}
}
