package k8s

import (
	"context"
	"strings"
	"testing"

	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
)

// 노드 셸은 호스트 안으로 들어가야 한다. nsenter 와 host namespace 가 빠지면 컨테이너 안에
// 갇혀 노드를 못 본다.
func TestNodeDebugPod_HostShellEntersTheHost(t *testing.T) {
	c := newFakeRealClient()

	res, err := c.CreateNodeDebugPod(context.Background(), NodeDebugPodOptions{NodeName: "n1"})
	if err != nil {
		t.Fatal(err)
	}

	pod, err := c.cs.CoreV1().Pods(res.Namespace).Get(context.Background(), res.PodName, metav1.GetOptions{})
	if err != nil {
		t.Fatal(err)
	}
	if !pod.Spec.HostPID || pod.Spec.Containers[0].Command[0] != "nsenter" {
		t.Fatalf("호스트로 들어가지 않는다: hostPID=%v cmd=%v", pod.Spec.HostPID, pod.Spec.Containers[0].Command)
	}
}

// kubectl, k9s 터미널은 호스트가 아니라 클러스터를 본다. 노드에 그 도구들이 깔려 있을 이유가
// 없으므로 이미지가 들고 와야 하고, nsenter 로 호스트에 들어가면 그 이미지를 쓰지 못한다.
func TestNodeDebugPod_ToolsShellRunsTheImage(t *testing.T) {
	c := newFakeRealClient()

	res, err := c.CreateNodeDebugPod(context.Background(), NodeDebugPodOptions{
		NodeName:   "n1",
		ToolsShell: true,
	})
	if err != nil {
		t.Fatal(err)
	}

	pod, _ := c.cs.CoreV1().Pods(res.Namespace).Get(context.Background(), res.PodName, metav1.GetOptions{})
	container := pod.Spec.Containers[0]
	if container.Command[0] == "nsenter" {
		t.Fatal("호스트로 들어가면 이미지의 kubectl, k9s 를 쓸 수 없다")
	}
	if !strings.Contains(container.Image, "ops-shell") {
		t.Fatalf("도구가 든 이미지가 아니다: %s", container.Image)
	}
	if pod.Spec.NodeName != "n1" {
		t.Fatalf("고른 노드에 뜨지 않는다: %s", pod.Spec.NodeName)
	}
}

// 도구 셸은 호스트를 건드릴 이유가 없다. privileged 로 띄우면 터미널 하나가 노드 전체 권한이 된다.
func TestNodeDebugPod_ToolsShellIsNotPrivileged(t *testing.T) {
	c := newFakeRealClient()

	res, _ := c.CreateNodeDebugPod(context.Background(), NodeDebugPodOptions{NodeName: "n1", ToolsShell: true})
	pod, _ := c.cs.CoreV1().Pods(res.Namespace).Get(context.Background(), res.PodName, metav1.GetOptions{})

	sc := pod.Spec.Containers[0].SecurityContext
	if pod.Spec.HostPID || pod.Spec.HostNetwork || (sc != nil && sc.Privileged != nil && *sc.Privileged) {
		t.Fatal("도구 셸이 호스트 권한을 들고 있다")
	}
}

// 클러스터를 보려면 자격이 있어야 한다. SA 가 없으면 kubectl 이 모든 호출에서 forbidden 을 받는다.
func TestNodeDebugPod_ToolsShellCarriesAServiceAccount(t *testing.T) {
	c := newFakeRealClient()

	res, _ := c.CreateNodeDebugPod(context.Background(), NodeDebugPodOptions{
		NodeName:       "n1",
		ToolsShell:     true,
		ServiceAccount: "aipaas-agent-installer",
	})
	pod, _ := c.cs.CoreV1().Pods(res.Namespace).Get(context.Background(), res.PodName, metav1.GetOptions{})

	if pod.Spec.ServiceAccountName != "aipaas-agent-installer" {
		t.Fatalf("SA 가 붙지 않았다: %q", pod.Spec.ServiceAccountName)
	}
}
