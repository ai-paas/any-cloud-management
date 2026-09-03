package controller

import (
	"testing"

	agentv1 "anycloud/agent/internal/gen/agent/v1"
)

// Dispatcher 의 mode-aware RPC 거부 검증.
// allowlist 통과한 명령이 mode 와 매칭 안 하면 PERMISSION_DENIED + COMMAND_NOT_FOR_MODE 반환.

func TestModeAllowsCommand_single_acceptsAll(t *testing.T) {
	d := New("instance-1", ModeSingle, nil, nil, permissiveLoader(t))
	for _, cmd := range []string{"LIST_PODS", "INSTALL_ADDON", "APPLY_MANIFEST", "BACKUP_ETCD"} {
		if !d.modeAllowsCommand(cmd) {
			t.Errorf("single mode rejected %s", cmd)
		}
	}
}

func TestModeAllowsCommand_core_readOnlyOnly(t *testing.T) {
	d := New("instance-1", ModeCore, nil, nil, permissiveLoader(t))

	// core 허용
	for _, cmd := range []string{"LIST_PODS", "GET_LOG", "GET_RESOURCE", "QUERY_METRICS", "BACKUP_ETCD"} {
		if !d.modeAllowsCommand(cmd) {
			t.Errorf("core mode rejected read-only %s", cmd)
		}
	}

	// core 거부 (mutating)
	for _, cmd := range []string{"INSTALL_ADDON", "APPLY_MANIFEST", "DELETE_RESOURCE", "APPLY_AGENT_CONFIG"} {
		if d.modeAllowsCommand(cmd) {
			t.Errorf("core mode incorrectly allowed mutating %s", cmd)
		}
	}
}

func TestModeAllowsCommand_installer_mutatingOnly(t *testing.T) {
	d := New("instance-1", ModeInstaller, nil, nil, permissiveLoader(t))

	// installer 허용
	for _, cmd := range []string{"INSTALL_ADDON", "APPLY_MANIFEST", "DELETE_RESOURCE", "ROLLBACK_HELM_RELEASE"} {
		if !d.modeAllowsCommand(cmd) {
			t.Errorf("installer mode rejected mutating %s", cmd)
		}
	}

	// installer 거부 (read-only)
	for _, cmd := range []string{"LIST_PODS", "GET_LOG", "QUERY_METRICS", "BACKUP_ETCD"} {
		if d.modeAllowsCommand(cmd) {
			t.Errorf("installer mode incorrectly allowed read-only %s", cmd)
		}
	}
}

func TestHandle_modeRejection_returnsCommandNotForMode(t *testing.T) {
	d := New("instance-1", ModeCore, nil, nil, permissiveLoader(t))
	cmd := &agentv1.CommandRequest{Type: agentv1.CommandType_INSTALL_ADDON}

	resp := d.Handle(cmd)

	if resp.GetStatus() != agentv1.Status_PERMISSION_DENIED {
		t.Errorf("expected PERMISSION_DENIED, got %s", resp.GetStatus())
	}
	if resp.GetErrorCode() != "COMMAND_NOT_FOR_MODE" {
		t.Errorf("expected COMMAND_NOT_FOR_MODE error code, got %s", resp.GetErrorCode())
	}
}

func TestHandle_modeAcceptance_installerInstallsAddon(t *testing.T) {
	// installer 가 INSTALL_ADDON 받으면 mode level 은 통과. dependency nil 이라 AGENT_UNAVAILABLE
	// (kube/helm client 부재) — mode 거부와 다른 에러 path.
	d := New("instance-1", ModeInstaller, nil, nil, permissiveLoader(t))
	cmd := &agentv1.CommandRequest{Type: agentv1.CommandType_INSTALL_ADDON}

	resp := d.Handle(cmd)

	if resp.GetErrorCode() == "COMMAND_NOT_FOR_MODE" {
		t.Errorf("installer should accept INSTALL_ADDON, got mode rejection")
	}
}

func TestNew_emptyMode_normalizedToSingle(t *testing.T) {
	d := New("instance-1", "", nil, nil, permissiveLoader(t))
	if d.mode != ModeSingle {
		t.Errorf("expected mode=single, got %s", d.mode)
	}
}
