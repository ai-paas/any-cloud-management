package helm_test

import (
	"os/exec"
	"strings"
	"testing"
)

// helm 이 없는 환경에서는 건너뛴다. CI 에는 있다.
func render(t *testing.T, extra ...string) string {
	t.Helper()
	if _, err := exec.LookPath("helm"); err != nil {
		t.Skip("helm 이 없어 건너뛴다")
	}
	args := append([]string{
		"template", "t", "./cluster-agent",
		"--set", "agent.registrationToken=x",
		"--set", "backend.grpcAddr=b:9090",
	}, extra...)
	out, err := exec.Command("helm", args...).CombinedOutput()
	if err != nil {
		t.Fatalf("helm template 실패: %v\n%s", err, out)
	}
	return string(out)
}

func agentModeOf(manifest string) []string {
	var modes []string
	lines := strings.Split(manifest, "\n")
	for i, l := range lines {
		if strings.Contains(l, "name: AGENT_MODE") && i+1 < len(lines) {
			modes = append(modes, strings.Trim(strings.TrimSpace(
				strings.TrimPrefix(strings.TrimSpace(lines[i+1]), "value:")), `"`))
		}
	}
	return modes
}

// single 은 한 pod 가 모든 명령을 처리한다. core 로 선언하면 그 pod 가 스스로 쓰기 명령을
// 거부해 파드 생성도 애드온 설치도 되지 않는다 — 실제로 그렇게 막혔다.
func TestSingleDeploymentDoesNotDeclareItselfCore(t *testing.T) {
	modes := agentModeOf(render(t))

	if len(modes) != 1 {
		t.Fatalf("single 은 pod 하나여야 한다, got %v", modes)
	}
	if modes[0] == "core" {
		t.Fatalf("single pod 가 core 로 떠 쓰기 명령이 막힌다")
	}
}

// split 은 권한을 나누려는 것이므로 두 역할이 그대로 나와야 한다.
func TestSplitDeploymentKeepsBothRoles(t *testing.T) {
	modes := agentModeOf(render(t, "--set", "deployment.mode=split"))

	if len(modes) != 2 {
		t.Fatalf("split 은 pod 둘이어야 한다, got %v", modes)
	}
	joined := strings.Join(modes, ",")
	if !strings.Contains(joined, "core") || !strings.Contains(joined, "installer") {
		t.Fatalf("split 에 core 와 installer 가 모두 있어야 한다, got %v", modes)
	}
}

// installer ClusterRole 의 rules 블록만 잘라낸다. core 역할의 pods 읽기 권한과 섞이면
// 없는 권한을 있다고 읽는다.
func installerRules(manifest string) string {
	i := strings.Index(manifest, "name: aipaas-agent-installer\n  labels:")
	if i < 0 {
		return ""
	}
	rest := manifest[i:]
	if j := strings.Index(rest, "\n---"); j > 0 {
		return rest[:j]
	}
	return rest
}

// 노드 셸은 임시 파드를 만들어 붙는다. 권한이 없으면 터미널 기능 전체가 forbidden 으로 죽는다 —
// "cannot create resource pods in namespace kube-system" 으로 실제로 막혔다.
func TestInstallerCanManageDebugPods(t *testing.T) {
	rules := installerRules(render(t))
	if rules == "" {
		t.Fatal("installer ClusterRole 을 찾지 못했다")
	}

	var podRule string
	for _, block := range strings.Split(rules, "- apiGroups:") {
		if strings.Contains(block, `"pods"`) {
			podRule = block
		}
	}
	if podRule == "" {
		t.Fatal("installer 에 pods rule 이 없어 노드 셸 파드를 만들 수 없다")
	}
	for _, verb := range []string{"create", "delete"} {
		if !strings.Contains(podRule, `"`+verb+`"`) {
			t.Fatalf("installer 의 pods 권한에 %s 가 없다: %s", verb, podRule)
		}
	}
}

// RBAC 에서 serviceaccounts/token 은 serviceaccounts 와 별개 리소스다. SA 를 만들 권한이
// 있어도 토큰은 발급하지 못해 kubeconfig 내려받기가 forbidden 으로 끝났다.
func TestAdminKubeconfigGrantsTokenIssuance(t *testing.T) {
	manifest := render(t, "--set", "rbac.adminKubeconfig.enabled=true")

	if !strings.Contains(manifest, `resources: ["serviceaccounts/token"]`) {
		t.Fatal("토큰 발급 권한이 없어 kubeconfig 내려받기가 forbidden 으로 끝난다")
	}
	if !strings.Contains(manifest, `resourceNames: ["aipaas-admin"]`) {
		t.Fatal("resourceNames 가 없으면 클러스터의 모든 SA 를 사칭할 수 있다")
	}
	for _, sa := range []string{"aipaas-agent-installer", "aipaas-agent-core"} {
		if !strings.Contains(manifest, sa) {
			t.Fatalf("%s 가 토큰 Role 에 묶이지 않았다", sa)
		}
	}
}

// 기본값에서는 admin SA 자체를 만들지 않는다. 토큰 권한만 남으면 등록형 클러스터에
// 쓰이지 않는 권한이 생긴다.
func TestTokenIssuanceIsAbsentWithoutAdminKubeconfig(t *testing.T) {
	if strings.Contains(render(t), "serviceaccounts/token") {
		t.Fatal("adminKubeconfig 가 꺼져 있는데 토큰 발급 권한이 렌더됐다")
	}
}
