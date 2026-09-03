// Package controller — agent 가 backend 로부터 받은 ControlMessage 를 처리하는 dispatcher.
//
// K8s ops (LIST_PODS / GET_LOG / GET_CLUSTER_INFO) + Helm ops (INSTALL_ADDON /
// UNINSTALL_ADDON / LIST_HELM_RELEASES). 모든 명령은 AllowList 통과 필수 (deny-all default).
//
// 본 파일은 dispatcher 핵심만 유지:
//   - Dispatcher struct + New 생성자
//   - Handle switch (CommandType → handler)
//   - commandAllowed (AllowList 검사)
//   - commandContext (timeout + Impersonation 부착)
//   - 공통 응답 빌더 (okResponse / errorResponse / failedWithData)
//   - 공통 helper (sortStable, getStringParam, parseInt64/parseBool, splitChartRef,
//     versionInRange/compareSemver/splitSemver, jsonUnmarshal)
//
// 도메인별 handler 는 별도 파일로 분리:
//   - k8s_resource.go : K8s 자원 명령 (LIST/GET/DELETE/APPLY/CLUSTER_INFO/KIND/RESOLVE)
//   - helm.go         : Helm release 명령 + per-release concurrency lock
//   - agent_config.go : GET_AGENT_CONFIG (allowlist snapshot 직렬화)
//   - observability.go: 관측성 명령 (Prometheus / Alertmanager / Grafana)
//   - apply_config.go : APPLY_AGENT_CONFIG (ConfigMap mutation)
package controller

import (
	"context"
	"fmt"
	"log/slog"
	"sort"
	"strconv"
	"strings"
	"time"

	"anycloud/agent/internal/config"
	agentv1 "anycloud/agent/internal/gen/agent/v1"
	"anycloud/agent/internal/helm"
	"anycloud/agent/internal/k8s"
	"google.golang.org/protobuf/types/known/structpb"
	"google.golang.org/protobuf/types/known/timestamppb"
)

// Dispatcher 는 명령 타입별 handler 를 라우팅. K8s Client + Helm Client + AllowList loader 주입.
//
// ConfigMap watch (config.Loader) 가 단일 reload path.
// kubectl edit / helm upgrade / Argo CD / Backend API 모두 ConfigMap 변경 → Loader watch → reload.
// Mode-aware RPC 거부. AGENT_MODE 가:
//   - "single"    : 모든 명령 허용 (기존 default 호환)
//   - "core"      : read-only RPC 만 허용 (LIST/GET/QUERY 등). mutating 호출 시 PERMISSION_DENIED
//   - "installer" : mutating RPC 만 허용 (INSTALL/APPLY/DELETE 등). read-only 호출 시 PERMISSION_DENIED
type Dispatcher struct {
	agentInstanceID string
	mode            string
	kube            k8s.Client
	helm            helm.Client
	allowlist       *config.Loader
}

// Mode constants — main.go 의 AGENT_MODE env 와 일치.
const (
	ModeSingle    = "single"
	ModeCore      = "core"
	ModeInstaller = "installer"
)

// coreCommands — read-only RPC set. installer pod 에선 거부됨.
var coreCommands = map[string]bool{
	"LIST_PODS":                   true,
	"GET_LOG":                     true,
	"GET_CLUSTER_INFO":            true,
	"LIST_RESOURCES":              true,
	"GET_RESOURCE":                true,
	"LIST_RESOURCE_KINDS":         true,
	"RESOLVE_RESOURCE":            true,
	"LIST_HELM_RELEASES":          true,
	"GET_HELM_RELEASE_STATUS":     true,
	"LIST_HELM_RELEASE_RESOURCES": true,
	"GET_HELM_RELEASE_HISTORY":    true,
	"QUERY_METRICS":               true,
	"LIST_METRIC_TARGETS":         true,
	"LIST_ALERTS":                 true,
	"LIST_ALERT_SILENCES":         true,
	"GET_DASHBOARD_URL":           true,
	"GENERATE_KUBECONFIG":         true,
	"GET_AGENT_CONFIG":            true,
	"GET_AGENT_HEALTH":            true,
	"BACKUP_ETCD":                 true,
	"BACKUP_PKI":                  true,
	"EXEC_POD":                    true,
}

// installerCommands — mutating RPC set. core pod 에선 거부됨.
var installerCommands = map[string]bool{
	"INSTALL_ADDON":                   true,
	"UPGRADE_ADDON":                   true,
	"UNINSTALL_ADDON":                 true,
	"DELETE_RESOURCE":                 true,
	"APPLY_MANIFEST":                  true,
	"ROLLBACK_HELM_RELEASE":           true,
	"INSTALL_OBSERVABILITY_STACK":     true,
	"CREATE_ALERT_SILENCE":            true,
	"DELETE_ALERT_SILENCE":            true,
	"CREATE_NODE_DEBUG_POD":           true,
	"APPLY_AGENT_CONFIG":              true,
	"ENSURE_AGENT_CONFIG_ANNOTATIONS": true,
	"GENERATE_CSR_TOKEN":              true,
}

// New — 일반 사용. 각 dependency 가 nil 이면 관련 명령이 AGENT_UNAVAILABLE 또는 PERMISSION_DENIED.
//
// allowlist == nil 이면 deny-all 강제 (production 에서는 절대 nil 안 됨).
// mode == "" → "single" 로 normalize (backward-compat).
func New(agentInstanceID string, mode string, kube k8s.Client, helmCli helm.Client,
	allowlist *config.Loader) *Dispatcher {
	if mode == "" {
		mode = ModeSingle
	}
	return &Dispatcher{
		agentInstanceID: agentInstanceID,
		mode:            mode,
		kube:            kube,
		helm:            helmCli,
		allowlist:       allowlist,
	}
}

func (d *Dispatcher) Handle(cmd *agentv1.CommandRequest) *agentv1.CommandResponse {
	if cmd == nil {
		return errorResponse(agentv1.Status_INVALID_PARAMS, "INVALID_REQUEST", "nil command")
	}
	slog.Debug("dispatch", slog.String("type", cmd.GetType().String()))

	// AllowList 검사 — 모든 명령에 적용. deny-all default.
	if !d.commandAllowed(cmd.GetType().String()) {
		return errorResponse(agentv1.Status_PERMISSION_DENIED, "COMMAND_NOT_ALLOWED",
			fmt.Sprintf("command %s not in allowlist", cmd.GetType()))
	}

	// Mode-aware RPC 거부. single 은 모든 명령 허용. core/installer 는 해당 set 안에 있어야.
	if !d.modeAllowsCommand(cmd.GetType().String()) {
		return errorResponse(agentv1.Status_PERMISSION_DENIED, "COMMAND_NOT_FOR_MODE",
			fmt.Sprintf("command %s not allowed in mode=%s", cmd.GetType(), d.mode))
	}

	ctx, cancel := commandContext(cmd)
	defer cancel()

	switch cmd.GetType() {
	case agentv1.CommandType_LIST_PODS:
		return d.listPods(ctx, cmd)
	case agentv1.CommandType_GET_LOG:
		return d.getLog(ctx, cmd)
	case agentv1.CommandType_GET_CLUSTER_INFO:
		return d.clusterInfo(ctx)
	case agentv1.CommandType_INSTALL_ADDON:
		return d.installAddon(ctx, cmd)
	case agentv1.CommandType_UPGRADE_ADDON:
		return d.upgradeAddon(ctx, cmd)
	case agentv1.CommandType_UNINSTALL_ADDON:
		return d.uninstallAddon(ctx, cmd)
	case agentv1.CommandType_LIST_RESOURCES: // K8s 자원 list (paginated).
		return d.listResources(ctx, cmd)
	case agentv1.CommandType_LIST_HELM_RELEASES: // helm list.
		return d.listHelmReleases(ctx, cmd)
	case agentv1.CommandType_LIST_HELM_RELEASE_RESOURCES: // helm release 의 K8s 자원 enumerate.
		return d.listHelmReleaseResources(ctx, cmd)
	case agentv1.CommandType_GET_HELM_RELEASE_STATUS: // 단일 helm release status.
		return d.getHelmReleaseStatus(ctx, cmd)
	case agentv1.CommandType_GET_HELM_RELEASE_HISTORY: // release revision 이력.
		return d.getHelmReleaseHistory(ctx, cmd)
	case agentv1.CommandType_ROLLBACK_HELM_RELEASE: // release 를 지정 revision 으로 rollback.
		return d.rollbackHelmRelease(ctx, cmd)
	case agentv1.CommandType_DELETE_RESOURCE: //
		return d.deleteResource(ctx, cmd)
	case agentv1.CommandType_GET_RESOURCE: //
		return d.getResource(ctx, cmd)
	case agentv1.CommandType_APPLY_MANIFEST: //
		return d.applyManifest(ctx, cmd)
	case agentv1.CommandType_INSTALL_OBSERVABILITY_STACK: //
		return d.installObservabilityStack(ctx, cmd)
	case agentv1.CommandType_QUERY_METRICS:
		return d.queryMetrics(ctx, cmd)
	case agentv1.CommandType_LIST_METRIC_TARGETS:
		return d.listMetricTargets(ctx, cmd)
	case agentv1.CommandType_LIST_ALERTS:
		return d.listAlerts(ctx, cmd)
	case agentv1.CommandType_LIST_ALERT_SILENCES:
		return d.listAlertSilences(ctx, cmd)
	case agentv1.CommandType_CREATE_ALERT_SILENCE:
		return d.createAlertSilence(ctx, cmd)
	case agentv1.CommandType_DELETE_ALERT_SILENCE:
		return d.deleteAlertSilence(ctx, cmd)
	case agentv1.CommandType_GET_DASHBOARD_URL:
		return d.getDashboardURL(ctx, cmd)
	case agentv1.CommandType_GENERATE_KUBECONFIG: //
		return d.generateKubeconfig(ctx, cmd)
	case agentv1.CommandType_CREATE_NODE_DEBUG_POD: //
		return d.createNodeDebugPod(ctx, cmd)
	case agentv1.CommandType_LIST_RESOURCE_KINDS: // discovery — cluster 가 지원하는 모든 kind (CRD 포함).
		return d.listResourceKinds(ctx)
	case agentv1.CommandType_RESOLVE_RESOURCE: // 단일 input (short/singular/plural) → 정규화된 ResolvedResource.
		return d.resolveResource(ctx, cmd)
	case agentv1.CommandType_GET_AGENT_CONFIG: // self-introspection — allowlist + resource_policy snapshot.
		return d.getAgentConfig(ctx, cmd)
	case agentv1.CommandType_APPLY_AGENT_CONFIG: // backend PUT — agent 가 자기 ConfigMap 갱신 → watch reload.
		return d.applyAgentConfig(ctx, cmd)
	case agentv1.CommandType_ENSURE_AGENT_CONFIG_ANNOTATIONS: // helm.sh/resource-policy=keep annotation 멱등 backfill.
		return d.ensureAgentConfigAnnotations(ctx, cmd)
	case agentv1.CommandType_BACKUP_ETCD: // control-plane node-agent 에 streaming RPC → buffered binary_payload.
		return d.backupEtcd(ctx, cmd)
	case agentv1.CommandType_BACKUP_PKI: // /etc/kubernetes/pki tar.gz → buffered binary_payload.
		return d.backupPki(ctx, cmd)
	default:
		return errorResponse(agentv1.Status_FAILED, "UNSUPPORTED_COMMAND",
			fmt.Sprintf("agent does not handle %s yet", cmd.GetType()))
	}
}

func (d *Dispatcher) commandAllowed(typeName string) bool {
	// allowlist 자체가 없으면 (test 시 nil) deny-all → 모든 명령 거부.
	if d.allowlist == nil {
		return false
	}
	return d.allowlist.Snapshot().IsCommandAllowed(typeName)
}

// modeAllowsCommand — split mode RPC 분기 검사.
//   - single    : 모든 명령 허용 (단일 pod 가 모든 명령 처리, 기존 default)
//   - core      : coreCommands 안에 있어야
//   - installer : installerCommands 안에 있어야
// 알 수 없는 mode 값은 conservative 하게 single 처럼 처리 (forward-compat).
func (d *Dispatcher) modeAllowsCommand(typeName string) bool {
	switch d.mode {
	case ModeCore:
		return coreCommands[typeName]
	case ModeInstaller:
		return installerCommands[typeName]
	default:
		return true
	}
}

// ===== helpers =====

func commandContext(cmd *agentv1.CommandRequest) (context.Context, context.CancelFunc) {
	timeout := cmd.GetTimeoutSeconds()
	if timeout <= 0 {
		timeout = 30
	}
	ctx, cancel := context.WithTimeout(context.Background(), time.Duration(timeout)*time.Second)
	// K8s Impersonation attach. backend 가 채워 보낸 impersonate_* field 가
	// 있으면 k8s.Impersonation 으로 변환해 ctx 에 부착. k8s.Client 의 List/Get/Apply/Delete/Logs
	// 가 ctx 에서 추출해 rest.Config.Impersonate 로 매핑된 client 사용. 비어 있으면 admin-equivalent.
	if user := strings.TrimSpace(cmd.GetImpersonateUser()); user != "" {
		imp := &k8s.Impersonation{
			User:   user,
			Groups: cmd.GetImpersonateGroups(),
		}
		if extras := cmd.GetImpersonateExtras(); len(extras) > 0 {
			imp.Extras = make(map[string][]string, len(extras))
			for k, v := range extras {
				if v == nil {
					continue
				}
				imp.Extras[k] = append([]string(nil), v.GetValues()...)
			}
		}
		ctx = k8s.ContextWithImpersonation(ctx, imp)
		slog.Debug("commandContext: impersonation attached",
			slog.String("user", user),
			slog.Int("groups", len(imp.Groups)),
			slog.String("type", cmd.GetType().String()))
	}
	return ctx, cancel
}

func okResponse(result *structpb.Struct) *agentv1.CommandResponse {
	return &agentv1.CommandResponse{
		Status:      agentv1.Status_OK,
		Result:      result,
		CompletedAt: timestamppb.Now(),
	}
}

func errorResponse(status agentv1.Status, code, message string) *agentv1.CommandResponse {
	return &agentv1.CommandResponse{
		Status: status, ErrorCode: code, ErrorMessage: message,
		CompletedAt: timestamppb.New(time.Now()),
	}
}

// failedWithData — failed-status 응답에 추가 metadata (result.data) 동봉. RESOLVE_RESOURCE 가
// suggestions 를 노출할 때 사용. status 는 caller 가 (예: INVALID_PARAMS) 결정.
func failedWithData(status agentv1.Status, code, message string, data *structpb.Struct) *agentv1.CommandResponse {
	return &agentv1.CommandResponse{
		Status:       status,
		ErrorCode:    code,
		ErrorMessage: message,
		Result:       data,
		CompletedAt:  timestamppb.New(time.Now()),
	}
}

// sortStable — sort.SliceStable wrapper. 명령 응답의 결정성 (UI 의 list 순서 안정) 보장.
func sortStable(s []interface{}, less func(i, j int) bool) {
	sort.SliceStable(s, less)
}

func getStringParam(cmd *agentv1.CommandRequest, key string) string {
	if cmd.GetParams() == nil {
		return ""
	}
	v, ok := cmd.GetParams().GetFields()[key]
	if !ok {
		return ""
	}
	return v.GetStringValue()
}

func parseInt64(s string, fallback int64) int64 {
	if s == "" {
		return fallback
	}
	v, err := strconv.ParseInt(s, 10, 64)
	if err != nil {
		return fallback
	}
	return v
}

func parseBool(s string) bool {
	if s == "" {
		return false
	}
	v, _ := strconv.ParseBool(s)
	return v
}

func splitChartRef(ref string) (repo, chart string, err error) {
	idx := strings.Index(ref, "/")
	if idx <= 0 || idx == len(ref)-1 {
		return "", "", fmt.Errorf("chart ref must be 'repo/name': %s", ref)
	}
	return ref[:idx], ref[idx+1:], nil
}

// versionInRange — semver-light. SemVer 형식 (예: "45.0.0") 만 지원, prerelease 무시.
// min == max 이면 정확히 일치만 허용.
func versionInRange(version, min, max string) bool {
	cmpMin := compareSemver(version, min)
	cmpMax := compareSemver(version, max)
	return cmpMin >= 0 && cmpMax <= 0
}

// compareSemver — major.minor.patch + optional v prefix. -1 / 0 / 1 반환.
func compareSemver(a, b string) int {
	pa := splitSemver(a)
	pb := splitSemver(b)
	for i := 0; i < 3; i++ {
		va, vb := pa[i], pb[i]
		if va < vb {
			return -1
		}
		if va > vb {
			return 1
		}
	}
	return 0
}

func splitSemver(s string) [3]int {
	s = strings.TrimPrefix(s, "v")
	// strip prerelease/build (after - or +).
	if i := strings.IndexAny(s, "-+"); i > 0 {
		s = s[:i]
	}
	parts := strings.SplitN(s, ".", 3)
	out := [3]int{}
	for i := 0; i < len(parts) && i < 3; i++ {
		v, _ := strconv.Atoi(parts[i])
		out[i] = v
	}
	return out
}

// jsonUnmarshal — encoding/json import 회피 (간단 명령 raw 만 받음). dispatcher 가 sync 핸들러라
// values 가 매우 큰 case 는 backend 에서 reject 권장.
func jsonUnmarshal(raw string, out *map[string]interface{}) error {
	return jsonStdUnmarshal([]byte(raw), out)
}

// jsonStdUnmarshal — 분리해서 빌드 태그로 swap 가능. 현재는 std encoding/json.
func jsonStdUnmarshal(data []byte, out *map[string]interface{}) error {
	return jsonDecode(data, out)
}
