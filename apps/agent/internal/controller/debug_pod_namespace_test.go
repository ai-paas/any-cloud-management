package controller

import (
	"testing"
)

// 도구 셸은 agent 의 SA 로 뜬다. SA 는 자기 namespace 안에서만 참조되므로 kube-system 에 만들면
// "serviceaccount not found" 로 거부된다 — 실제로 그렇게 막혔다.
func TestDebugPodNamespace_ToolsShellLandsWhereTheAgentServiceAccountLives(t *testing.T) {
	t.Setenv("AGENT_NAMESPACE", "aipaas-system")

	if got := debugPodNamespace("", true); got != "aipaas-system" {
		t.Fatalf("SA 가 없는 namespace 에 띄운다: %s", got)
	}
}

// 노드 셸은 호스트로 들어가는 것이라 SA 가 필요 없다. 기존 위치를 지킨다.
func TestDebugPodNamespace_HostShellStaysInKubeSystem(t *testing.T) {
	if got := debugPodNamespace("", false); got != "kube-system" {
		t.Fatalf("노드 셸 위치가 바뀌었다: %s", got)
	}
}

// 운영자가 고른 namespace 를 덮어쓰면 allowlist 로 좁혀놓은 범위를 벗어난다.
func TestDebugPodNamespace_ExplicitRequestWins(t *testing.T) {
	t.Setenv("AGENT_NAMESPACE", "aipaas-system")

	if got := debugPodNamespace("ops", true); got != "ops" {
		t.Fatalf("요청한 namespace 를 무시한다: %s", got)
	}
}
