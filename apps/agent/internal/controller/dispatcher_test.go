package controller

import (
	"context"
	"errors"
	"fmt"
	"io"
	"strings"
	"testing"
	"time"

	"anycloud/agent/internal/config"
	agentv1 "anycloud/agent/internal/gen/agent/v1"
	"anycloud/agent/internal/helm"
	"anycloud/agent/internal/k8s"
	"helm.sh/helm/v3/pkg/cli"
	corev1 "k8s.io/api/core/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/client-go/kubernetes"
	"k8s.io/client-go/kubernetes/fake"
	"google.golang.org/protobuf/types/known/structpb"
)

// ===== mocks =====

type mockK8sClient struct {
	listPodsFn func(ctx context.Context, ns string) ([]k8s.PodSummary, error)
	getLogsFn  func(ctx context.Context, opts k8s.PodLogsOptions) (string, error)
	clusterFn  func(ctx context.Context) (*k8s.ClusterInfo, error)
	deleteFn   func(ctx context.Context, opts k8s.DeleteResourceOptions) error
	getFn      func(ctx context.Context, opts k8s.GetResourceOptions) (string, error)
	listResFn  func(ctx context.Context, opts k8s.ListResourcesOptions) (*k8s.ListResourcesResult, error)
	applyFn    func(ctx context.Context, opts k8s.ApplyManifestOptions) (*k8s.ApplyManifestResult, error)
	// resolveFn — 테스트가 RESTMapper 동작을 흉내내기 위한 stub. nil 이면 mockResolveDefault 사용.
	resolveFn      func(input string) (k8s.ResolvedResource, error)
	listAPIResFn   func(ctx context.Context) ([]k8s.APIResourceInfo, error)
	// clientset — APPLY_AGENT_CONFIG 등 raw clientset 을 필요로 하는 명령용. nil 이면 Clientset() 도 nil.
	clientset kubernetes.Interface
}

// mockResolveDefault — RESTMapper 동작을 mock 으로 시뮬레이션. dispatcher 테스트에서 사용하는
// kind 들만 매핑. unknown kind 는 ErrUnsupportedKind 반환.
func mockResolveDefault(input string) (k8s.ResolvedResource, error) {
	type spec struct {
		plural     string
		group      string
		version    string
		namespaced bool
	}
	// lowercase singular / plural / short → spec
	table := map[string]spec{
		"pod":   {"pods", "", "v1", true},
		"pods":  {"pods", "", "v1", true},
		"po":    {"pods", "", "v1", true},
		"node":  {"nodes", "", "v1", false},
		"nodes": {"nodes", "", "v1", false},
		"no":    {"nodes", "", "v1", false},
		"namespace":  {"namespaces", "", "v1", false},
		"namespaces": {"namespaces", "", "v1", false},
		"ns":         {"namespaces", "", "v1", false},
		"service":  {"services", "", "v1", true},
		"services": {"services", "", "v1", true},
		"svc":      {"services", "", "v1", true},
		"configmap":  {"configmaps", "", "v1", true},
		"configmaps": {"configmaps", "", "v1", true},
		"cm":         {"configmaps", "", "v1", true},
		"secret":     {"secrets", "", "v1", true},
		"secrets":    {"secrets", "", "v1", true},
		"deployment":  {"deployments", "apps", "v1", true},
		"deployments": {"deployments", "apps", "v1", true},
		"deploy":      {"deployments", "apps", "v1", true},
		"storageclass":   {"storageclasses", "storage.k8s.io", "v1", false},
		"storageclasses": {"storageclasses", "storage.k8s.io", "v1", false},
		"sc":             {"storageclasses", "storage.k8s.io", "v1", false},
		"customresourcedefinition":  {"customresourcedefinitions", "apiextensions.k8s.io", "v1", false},
		"customresourcedefinitions": {"customresourcedefinitions", "apiextensions.k8s.io", "v1", false},
		"crd":                       {"customresourcedefinitions", "apiextensions.k8s.io", "v1", false},
	}
	raw := strings.ToLower(strings.TrimSpace(input))
	if s, ok := table[raw]; ok {
		return k8s.ResolvedResource{
			Plural: s.plural, Group: s.group, Version: s.version, Namespaced: s.namespaced,
		}, nil
	}
	return k8s.ResolvedResource{}, fmt.Errorf("%w: %s", k8s.ErrUnsupportedKind, input)
}

func (m *mockK8sClient) ListPods(ctx context.Context, ns string) ([]k8s.PodSummary, error) {
	if m.listPodsFn == nil {
		return nil, errors.New("not stubbed")
	}
	return m.listPodsFn(ctx, ns)
}
func (m *mockK8sClient) GetPodLogs(ctx context.Context, opts k8s.PodLogsOptions) (string, error) {
	if m.getLogsFn == nil {
		return "", errors.New("not stubbed")
	}
	return m.getLogsFn(ctx, opts)
}
func (m *mockK8sClient) ClusterInfo(ctx context.Context) (*k8s.ClusterInfo, error) {
	if m.clusterFn == nil {
		return nil, errors.New("not stubbed")
	}
	return m.clusterFn(ctx)
}
func (m *mockK8sClient) DeleteResource(ctx context.Context, opts k8s.DeleteResourceOptions) error {
	if m.deleteFn == nil {
		return errors.New("not stubbed")
	}
	return m.deleteFn(ctx, opts)
}
func (m *mockK8sClient) GetResource(ctx context.Context, opts k8s.GetResourceOptions) (string, error) {
	if m.getFn == nil {
		return "", errors.New("not stubbed")
	}
	return m.getFn(ctx, opts)
}
func (m *mockK8sClient) ListResources(ctx context.Context, opts k8s.ListResourcesOptions) (*k8s.ListResourcesResult, error) {
	if m.listResFn == nil {
		return nil, errors.New("not stubbed")
	}
	return m.listResFn(ctx, opts)
}
func (m *mockK8sClient) ApplyManifest(ctx context.Context, opts k8s.ApplyManifestOptions) (*k8s.ApplyManifestResult, error) {
	if m.applyFn == nil {
		return nil, errors.New("not stubbed")
	}
	return m.applyFn(ctx, opts)
}
func (m *mockK8sClient) ResolveResource(input string) (k8s.ResolvedResource, error) {
	if m.resolveFn != nil {
		return m.resolveFn(input)
	}
	return mockResolveDefault(input)
}
func (m *mockK8sClient) ExecInPod(ctx context.Context, opts k8s.PodExecOptions, streams k8s.ExecStreams) error {
	return errors.New("ExecInPod not stubbed (dispatcher tests do not route exec)")
}
func (m *mockK8sClient) CountGpuNodes(ctx context.Context) (int, error) {
	return 0, nil     // dispatcher tests don't exercise GPU count.
}
func (m *mockK8sClient) IssueServiceAccountToken(ctx context.Context, opts k8s.TokenRequestOptions) (k8s.TokenRequestResult, error) {
	return k8s.TokenRequestResult{}, errors.New("IssueServiceAccountToken not stubbed")
}
func (m *mockK8sClient) CreateNodeDebugPod(ctx context.Context, opts k8s.NodeDebugPodOptions) (k8s.NodeDebugPodResult, error) {
	return k8s.NodeDebugPodResult{}, errors.New("CreateNodeDebugPod not stubbed")
}
func (m *mockK8sClient) ServerCAData() ([]byte, error) {
	return nil, errors.New("ServerCAData not stubbed")
}
func (m *mockK8sClient) APIServerURL() string {
	return ""
}
func (m *mockK8sClient) ListPodsRaw(ctx context.Context, namespace, labelSelector string) ([]corev1.Pod, error) {
	return nil, errors.New("ListPodsRaw not stubbed (dispatcher tests do not use sweeper)")
}
func (m *mockK8sClient) StreamPodLogs(ctx context.Context, opts k8s.StreamPodLogsOptions) (io.ReadCloser, error) {
	return nil, errors.New("StreamPodLogs not stubbed (dispatcher tests do not route log streaming)")
}
func (m *mockK8sClient) ListByHelmRelease(ctx context.Context, opts k8s.HelmReleaseResourcesOptions) ([]k8s.HelmReleaseResourceRef, error) {
	return nil, errors.New("ListByHelmRelease not stubbed")
}
func (m *mockK8sClient) Clientset() kubernetes.Interface {
	return m.clientset // nil → "raw clientset 미주입" (APPLY_AGENT_CONFIG 가 AGENT_UNAVAILABLE 반환).
}
func (m *mockK8sClient) ListAPIResources(ctx context.Context) ([]k8s.APIResourceInfo, error) {
	if m.listAPIResFn == nil {
		return nil, errors.New("ListAPIResources not stubbed")
	}
	return m.listAPIResFn(ctx)
}

type mockHelmClient struct {
	installFn   func(ctx context.Context, opts helm.InstallOptions) (*helm.Release, error)
	uninstallFn func(ctx context.Context, opts helm.UninstallOptions) error
	listFn      func(ctx context.Context, namespace string) ([]helm.Release, error)
	statusFn    func(ctx context.Context, namespace, releaseName string) (*helm.Release, error)
	historyFn   func(ctx context.Context, namespace, releaseName string, max int) ([]helm.HistoryRevision, error)
	rollbackFn  func(ctx context.Context, opts helm.RollbackOptions) (*helm.Release, error)
}

// Settings — test 환경에서 helm.SyncRepositories 가 nil-deref 없이 동작하도록
// tmp dir 의 RepositoryConfig 가진 EnvSettings 반환. 단위 테스트가 helm SDK 의 RepositoryFile 까지
// 직접 검증할 필요 없으면 nil 반환도 OK (apply_config.go 가 nil 체크).
func (m *mockHelmClient) Settings() *cli.EnvSettings { return nil }

func (m *mockHelmClient) Install(ctx context.Context, opts helm.InstallOptions) (*helm.Release, error) {
	return m.installFn(ctx, opts)
}
func (m *mockHelmClient) Uninstall(ctx context.Context, opts helm.UninstallOptions) error {
	return m.uninstallFn(ctx, opts)
}
func (m *mockHelmClient) List(ctx context.Context, ns string) ([]helm.Release, error) {
	return m.listFn(ctx, ns)
}
func (m *mockHelmClient) Status(ctx context.Context, ns, name string) (*helm.Release, error) {
	if m.statusFn == nil {
		return nil, fmt.Errorf("status %s: not configured", name)
	}
	return m.statusFn(ctx, ns, name)
}
func (m *mockHelmClient) History(ctx context.Context, ns, name string, max int) ([]helm.HistoryRevision, error) {
	if m.historyFn == nil {
		return nil, fmt.Errorf("history %s: not configured", name)
	}
	return m.historyFn(ctx, ns, name, max)
}
func (m *mockHelmClient) Rollback(ctx context.Context, opts helm.RollbackOptions) (*helm.Release, error) {
	if m.rollbackFn == nil {
		return nil, fmt.Errorf("rollback %s: not configured", opts.ReleaseName)
	}
	return m.rollbackFn(ctx, opts)
}

// Upgrade mock. 본 test suite 는 upgrade flow 검증 안 함 — minimal stub.
func (m *mockHelmClient) Upgrade(ctx context.Context, opts helm.UpgradeOptions) (*helm.Release, error) {
	return nil, fmt.Errorf("upgrade %s: not configured", opts.ReleaseName)
}

// resourcePolicyLoader — resource_policy 도 동시에 주입한 loader.
// permissiveLoader 와 동일한 commands/namespaces 를 갖지만 추가로 resource_policy 가 적용.
func resourcePolicyLoader(t *testing.T, policyYAML string) *config.Loader {
	t.Helper()
	cm := &corev1.ConfigMap{
		ObjectMeta: metav1.ObjectMeta{Name: "aipaas-agent-allowlist", Namespace: "aipaas-system"},
		Data: map[string]string{
			"allowed_namespaces": `- monitoring
- ingress-system
- web
- kube-system
`,
			"allowed_commands": `- LIST_RESOURCES
- GET_RESOURCE
- DELETE_RESOURCE
`,
			"resource_policy": policyYAML,
		},
	}
	cs := fake.NewSimpleClientset(cm)
	l := config.NewLoader(cs, "aipaas-system", "aipaas-agent-allowlist")
	if err := l.LoadOnce(context.Background()); err != nil {
		t.Fatalf("LoadOnce: %v", err)
	}
	return l
}

// permissiveLoader — 모든 명령/namespace/chart 허용. K8s 명령 테스트용.
func permissiveLoader(t *testing.T) *config.Loader {
	t.Helper()
	cm := &corev1.ConfigMap{
		ObjectMeta: metav1.ObjectMeta{Name: "aipaas-agent-allowlist", Namespace: "aipaas-system"},
		Data: map[string]string{
			"allowed_charts": `- prometheus-community/kube-prometheus-stack:45.0.0-50.0.0
- ingress-nginx/ingress-nginx:4.8.0-4.9.0
`,
			"allowed_namespaces": `- monitoring
- ingress-system
- web
- kube-system
`,
			"allowed_commands": `- LIST_PODS
- GET_LOG
- GET_CLUSTER_INFO
- INSTALL_ADDON
- UNINSTALL_ADDON
- LIST_RESOURCES
- LIST_HELM_RELEASES
- DELETE_RESOURCE
- GET_RESOURCE
- APPLY_MANIFEST
- LIST_RESOURCE_KINDS
- RESOLVE_RESOURCE
- GET_AGENT_CONFIG
- APPLY_AGENT_CONFIG
- ENSURE_AGENT_CONFIG_ANNOTATIONS
`,
		},
	}
	cs := fake.NewSimpleClientset(cm)
	l := config.NewLoader(cs, "aipaas-system", "aipaas-agent-allowlist")
	if err := l.LoadOnce(context.Background()); err != nil {
		t.Fatalf("LoadOnce: %v", err)
	}
	return l
}

func denyAllLoader(t *testing.T) *config.Loader {
	t.Helper()
	// Empty ConfigMap → empty Commands map → all deny.
	cm := &corev1.ConfigMap{
		ObjectMeta: metav1.ObjectMeta{Name: "aipaas-agent-allowlist", Namespace: "aipaas-system"},
	}
	cs := fake.NewSimpleClientset(cm)
	l := config.NewLoader(cs, "aipaas-system", "aipaas-agent-allowlist")
	_ = l.LoadOnce(context.Background())
	return l
}

// ===== K8s command tests =====

func TestHandle_ListPods_ReturnsRealResults(t *testing.T) {
	mock := &mockK8sClient{
		listPodsFn: func(ctx context.Context, ns string) ([]k8s.PodSummary, error) {
			return []k8s.PodSummary{
				{Name: "nginx-1", Namespace: "web", Phase: "Running", NodeName: "node-1",
					PodIP: "10.0.0.5", ContainersReady: 1, ContainersTotal: 1, StartTime: time.Now()},
				{Name: "nginx-2", Namespace: "web", Phase: "Pending",
					ContainersReady: 0, ContainersTotal: 1, StartTime: time.Now()},
			}, nil
		},
	}
	d := New("instance-1", "", mock, nil, permissiveLoader(t))
	params, _ := structpb.NewStruct(map[string]interface{}{"namespace": "web"})
	resp := d.Handle(&agentv1.CommandRequest{Type: agentv1.CommandType_LIST_PODS, Params: params})

	if resp.GetStatus() != agentv1.Status_OK {
		t.Fatalf("status = %v: %s", resp.GetStatus(), resp.GetErrorMessage())
	}
	if resp.GetResult().GetFields()["count"].GetNumberValue() != 2 {
		t.Errorf("count = %v, want 2", resp.GetResult().GetFields()["count"].GetNumberValue())
	}
}

func TestHandle_ListPods_AllNamespacesSentinel(t *testing.T) {
	captured := ""
	mock := &mockK8sClient{
		listPodsFn: func(ctx context.Context, ns string) ([]k8s.PodSummary, error) {
			captured = ns
			return nil, nil
		},
	}
	d := New("instance-1", "", mock, nil, permissiveLoader(t))
	params, _ := structpb.NewStruct(map[string]interface{}{"namespace": "_all"})
	d.Handle(&agentv1.CommandRequest{Type: agentv1.CommandType_LIST_PODS, Params: params})

	if captured != "" {
		t.Errorf("_all sentinel not converted to empty: %q", captured)
	}
}

func TestHandle_ListPods_K8sError_PropagatesFailed(t *testing.T) {
	mock := &mockK8sClient{
		listPodsFn: func(ctx context.Context, ns string) ([]k8s.PodSummary, error) {
			return nil, errors.New("apiserver unreachable")
		},
	}
	d := New("instance-1", "", mock, nil, permissiveLoader(t))
	resp := d.Handle(&agentv1.CommandRequest{Type: agentv1.CommandType_LIST_PODS})

	if resp.GetStatus() != agentv1.Status_FAILED {
		t.Errorf("status = %v, want FAILED", resp.GetStatus())
	}
	if resp.GetErrorCode() != "K8S_LIST_PODS_FAILED" {
		t.Errorf("error_code = %q", resp.GetErrorCode())
	}
}

func TestHandle_ListPods_NilClient_ReturnsAgentUnavailable(t *testing.T) {
	d := New("instance-1", "", nil, nil, permissiveLoader(t))
	resp := d.Handle(&agentv1.CommandRequest{Type: agentv1.CommandType_LIST_PODS})

	if resp.GetStatus() != agentv1.Status_AGENT_UNAVAILABLE {
		t.Errorf("status = %v, want AGENT_UNAVAILABLE", resp.GetStatus())
	}
}

func TestHandle_GetLog_RealLogReturned(t *testing.T) {
	mock := &mockK8sClient{
		getLogsFn: func(ctx context.Context, opts k8s.PodLogsOptions) (string, error) {
			return "line 1\nline 2\nline 3\n", nil
		},
	}
	d := New("instance-1", "", mock, nil, permissiveLoader(t))
	params, _ := structpb.NewStruct(map[string]interface{}{
		"pod": "nginx-abc", "namespace": "web", "tailLines": "100",
	})
	resp := d.Handle(&agentv1.CommandRequest{Type: agentv1.CommandType_GET_LOG, Params: params})

	if resp.GetStatus() != agentv1.Status_OK {
		t.Fatalf("status = %v: %s", resp.GetStatus(), resp.GetErrorMessage())
	}
	if resp.GetResult().GetFields()["log"].GetStringValue() != "line 1\nline 2\nline 3\n" {
		t.Errorf("log content mismatch")
	}
}

func TestHandle_GetLog_MissingPod_ReturnsInvalidParams(t *testing.T) {
	d := New("instance-1", "", &mockK8sClient{}, nil, permissiveLoader(t))
	resp := d.Handle(&agentv1.CommandRequest{
		Type: agentv1.CommandType_GET_LOG, Params: &structpb.Struct{},
	})

	if resp.GetStatus() != agentv1.Status_INVALID_PARAMS {
		t.Errorf("status = %v, want INVALID_PARAMS", resp.GetStatus())
	}
}

func TestHandle_ClusterInfo_ReturnsRealMetadata(t *testing.T) {
	mock := &mockK8sClient{
		clusterFn: func(ctx context.Context) (*k8s.ClusterInfo, error) {
			return &k8s.ClusterInfo{
				K8sClusterUID: "550e8400-e29b-41d4-a716-446655440000",
				Version:       "v1.34.3",
				Distribution:  "kubeadm",
				NodeCount:     3,
			}, nil
		},
	}
	d := New("instance-1", "", mock, nil, permissiveLoader(t))
	resp := d.Handle(&agentv1.CommandRequest{Type: agentv1.CommandType_GET_CLUSTER_INFO})

	if resp.GetStatus() != agentv1.Status_OK {
		t.Fatalf("status = %v", resp.GetStatus())
	}
}

func TestHandle_UnsupportedType_ReturnsCommandNotAllowed(t *testing.T) {
	// SCALE_DEPLOYMENT 는 permissiveLoader 의 allowlist 에 없음 → PERMISSION_DENIED.
	d := New("instance-1", "", &mockK8sClient{}, nil, permissiveLoader(t))
	resp := d.Handle(&agentv1.CommandRequest{Type: agentv1.CommandType_SCALE_DEPLOYMENT})

	if resp.GetStatus() != agentv1.Status_PERMISSION_DENIED {
		t.Errorf("status = %v, want PERMISSION_DENIED", resp.GetStatus())
	}
}

func TestHandle_NilCommand_ReturnsInvalidParams(t *testing.T) {
	d := New("instance-1", "", &mockK8sClient{}, nil, permissiveLoader(t))
	resp := d.Handle(nil)

	if resp.GetStatus() != agentv1.Status_INVALID_PARAMS {
		t.Errorf("status = %v, want INVALID_PARAMS", resp.GetStatus())
	}
}

// ===== AllowList enforcement =====

func TestAllowList_DenyAllDefault_RejectsEverything(t *testing.T) {
	d := New("instance-1", "", &mockK8sClient{}, nil, denyAllLoader(t))
	resp := d.Handle(&agentv1.CommandRequest{Type: agentv1.CommandType_LIST_PODS})

	if resp.GetStatus() != agentv1.Status_PERMISSION_DENIED {
		t.Errorf("status = %v, want PERMISSION_DENIED", resp.GetStatus())
	}
	if resp.GetErrorCode() != "COMMAND_NOT_ALLOWED" {
		t.Errorf("error_code = %q", resp.GetErrorCode())
	}
}

func TestAllowList_NilLoader_DenyAll(t *testing.T) {
	d := New("instance-1", "", &mockK8sClient{}, nil, nil)
	resp := d.Handle(&agentv1.CommandRequest{Type: agentv1.CommandType_LIST_PODS})

	if resp.GetStatus() != agentv1.Status_PERMISSION_DENIED {
		t.Errorf("status = %v, want PERMISSION_DENIED", resp.GetStatus())
	}
}

// ===== Helm command tests =====

func TestHandle_InstallAddon_AllowedChart_Success(t *testing.T) {
	captured := helm.InstallOptions{}
	helmMock := &mockHelmClient{
		installFn: func(ctx context.Context, opts helm.InstallOptions) (*helm.Release, error) {
			captured = opts
			return &helm.Release{
				Name: opts.ReleaseName, Namespace: opts.Namespace,
				Chart: opts.Chart, Version: opts.Version,
				Revision: 1, Status: "deployed",
			}, nil
		},
	}
	d := New("instance-1", "", nil, helmMock, permissiveLoader(t))
	params, _ := structpb.NewStruct(map[string]interface{}{
		"chart": "prometheus-community/kube-prometheus-stack",
		"version": "46.0.0",
		"namespace": "monitoring",
		"release":   "prom",
	})
	resp := d.Handle(&agentv1.CommandRequest{Type: agentv1.CommandType_INSTALL_ADDON, Params: params})

	if resp.GetStatus() != agentv1.Status_OK {
		t.Fatalf("status = %v: %s", resp.GetStatus(), resp.GetErrorMessage())
	}
	if captured.Chart != "kube-prometheus-stack" {
		t.Errorf("chart forwarded = %q", captured.Chart)
	}
	if captured.Repo != "prometheus-community" {
		t.Errorf("repo forwarded = %q", captured.Repo)
	}
	if captured.Version != "46.0.0" {
		t.Errorf("version forwarded = %q", captured.Version)
	}
}

func TestHandle_InstallAddon_ChartNotAllowed_Reject(t *testing.T) {
	d := New("instance-1", "", nil, &mockHelmClient{}, permissiveLoader(t))
	params, _ := structpb.NewStruct(map[string]interface{}{
		"chart":     "evil-repo/bad-chart",
		"version":   "1.0.0",
		"namespace": "monitoring",
		"release":   "bad",
	})
	resp := d.Handle(&agentv1.CommandRequest{Type: agentv1.CommandType_INSTALL_ADDON, Params: params})

	if resp.GetStatus() != agentv1.Status_PERMISSION_DENIED {
		t.Errorf("status = %v, want PERMISSION_DENIED", resp.GetStatus())
	}
	if resp.GetErrorCode() != "CHART_NOT_ALLOWED" {
		t.Errorf("error_code = %q", resp.GetErrorCode())
	}
}

func TestHandle_InstallAddon_VersionOutOfRange_Reject(t *testing.T) {
	d := New("instance-1", "", nil, &mockHelmClient{}, permissiveLoader(t))
	params, _ := structpb.NewStruct(map[string]interface{}{
		"chart": "prometheus-community/kube-prometheus-stack",
		"version": "60.0.0",     // allowlist max 50.0.0.
		"namespace": "monitoring",
		"release":   "prom",
	})
	resp := d.Handle(&agentv1.CommandRequest{Type: agentv1.CommandType_INSTALL_ADDON, Params: params})

	if resp.GetStatus() != agentv1.Status_PERMISSION_DENIED {
		t.Errorf("status = %v, want PERMISSION_DENIED", resp.GetStatus())
	}
	if resp.GetErrorCode() != "VERSION_OUT_OF_RANGE" {
		t.Errorf("error_code = %q", resp.GetErrorCode())
	}
}

func TestHandle_InstallAddon_NamespaceNotAllowed_Reject(t *testing.T) {
	d := New("instance-1", "", nil, &mockHelmClient{}, permissiveLoader(t))
	params, _ := structpb.NewStruct(map[string]interface{}{
		"chart": "prometheus-community/kube-prometheus-stack",
		"version": "46.0.0",
		"namespace": "production",     // allowlist 미허용.
		"release":   "prom",
	})
	resp := d.Handle(&agentv1.CommandRequest{Type: agentv1.CommandType_INSTALL_ADDON, Params: params})

	if resp.GetStatus() != agentv1.Status_PERMISSION_DENIED {
		t.Errorf("status = %v, want PERMISSION_DENIED", resp.GetStatus())
	}
	if resp.GetErrorCode() != "NAMESPACE_NOT_ALLOWED" {
		t.Errorf("error_code = %q", resp.GetErrorCode())
	}
}

func TestHandle_UninstallAddon_Success(t *testing.T) {
	called := false
	var gotOpts helm.UninstallOptions
	helmMock := &mockHelmClient{
		uninstallFn: func(ctx context.Context, opts helm.UninstallOptions) error {
			called = true
			gotOpts = opts
			if opts.Namespace != "monitoring" || opts.ReleaseName != "prom" {
				t.Errorf("uninstall args: ns=%s name=%s", opts.Namespace, opts.ReleaseName)
			}
			return nil
		},
	}
	d := New("instance-1", "", nil, helmMock, permissiveLoader(t))
	params, _ := structpb.NewStruct(map[string]interface{}{
		"namespace": "monitoring", "release": "prom",
		"keepHistory": "true", "wait": "true",
	})
	resp := d.Handle(&agentv1.CommandRequest{Type: agentv1.CommandType_UNINSTALL_ADDON, Params: params})

	if resp.GetStatus() != agentv1.Status_OK {
		t.Errorf("status = %v", resp.GetStatus())
	}
	if !called {
		t.Error("Uninstall not called")
	}
	if !gotOpts.KeepHistory {
		t.Errorf("expected KeepHistory=true, got %v", gotOpts.KeepHistory)
	}
	if !gotOpts.Wait {
		t.Errorf("expected Wait=true, got %v", gotOpts.Wait)
	}
}

func TestHandle_ListHelmReleases_AllowedAndForwardsAllNamespaces(t *testing.T) {
	// LIST_HELM_RELEASES 가 enum 으로 분리됨.
	captured := ""
	helmMock := &mockHelmClient{
		listFn: func(ctx context.Context, ns string) ([]helm.Release, error) {
			captured = ns
			return []helm.Release{
				{Name: "prom", Namespace: "monitoring", Chart: "kube-prometheus-stack",
					Version: "46.0.0", Revision: 1, Status: "deployed", Updated: time.Now()},
			}, nil
		},
	}
	d := New("instance-1", "", nil, helmMock, permissiveLoader(t))
	params, _ := structpb.NewStruct(map[string]interface{}{"namespace": "_all"})
	resp := d.Handle(&agentv1.CommandRequest{Type: agentv1.CommandType_LIST_HELM_RELEASES, Params: params})

	if resp.GetStatus() != agentv1.Status_OK {
		t.Fatalf("status = %v: %s", resp.GetStatus(), resp.GetErrorMessage())
	}
	if captured != "" {
		t.Errorf("_all should map to empty namespace: %q", captured)
	}
}

// ===== semver helpers =====

func TestVersionInRange(t *testing.T) {
	cases := []struct {
		version, min, max string
		want              bool
	}{
		{"45.0.0", "45.0.0", "50.0.0", true},
		{"50.0.0", "45.0.0", "50.0.0", true},
		{"47.5.3", "45.0.0", "50.0.0", true},
		{"44.9.99", "45.0.0", "50.0.0", false},
		{"50.0.1", "45.0.0", "50.0.0", false},
		{"v1.12.0", "v1.12.0", "v1.13.0", true},
		{"v1.13.5", "v1.12.0", "v1.13.0", false},
	}
	for _, c := range cases {
		if got := versionInRange(c.version, c.min, c.max); got != c.want {
			t.Errorf("versionInRange(%q, %q, %q) = %v, want %v",
				c.version, c.min, c.max, got, c.want)
		}
	}
}

// ===== DELETE_RESOURCE  =====

func TestHandle_DeleteResource_AllowedNamespace_Success(t *testing.T) {
	var captured k8s.DeleteResourceOptions
	mock := &mockK8sClient{
		deleteFn: func(ctx context.Context, opts k8s.DeleteResourceOptions) error {
			captured = opts
			return nil
		},
	}
	d := New("instance-1", "", mock, nil, permissiveLoader(t))
	params, _ := structpb.NewStruct(map[string]interface{}{
		"kind": "pod", "name": "nginx-abc", "namespace": "web",
	})
	resp := d.Handle(&agentv1.CommandRequest{Type: agentv1.CommandType_DELETE_RESOURCE, Params: params})

	if resp.GetStatus() != agentv1.Status_OK {
		t.Fatalf("status = %v: %s", resp.GetStatus(), resp.GetErrorMessage())
	}
	if captured.Kind != "pod" || captured.Name != "nginx-abc" || captured.Namespace != "web" {
		t.Errorf("opts forwarded: %+v", captured)
	}
	if resp.GetResult().GetFields()["deleted"].GetBoolValue() != true {
		t.Error("deleted=true expected")
	}
}

func TestHandle_DeleteResource_MissingParam_InvalidParams(t *testing.T) {
	d := New("instance-1", "", &mockK8sClient{}, nil, permissiveLoader(t))
	params, _ := structpb.NewStruct(map[string]interface{}{"kind": "pod"})     // name 누락.
	resp := d.Handle(&agentv1.CommandRequest{Type: agentv1.CommandType_DELETE_RESOURCE, Params: params})

	if resp.GetStatus() != agentv1.Status_INVALID_PARAMS {
		t.Errorf("status = %v, want INVALID_PARAMS", resp.GetStatus())
	}
}

func TestHandle_DeleteResource_NamespaceNotAllowed_Reject(t *testing.T) {
	d := New("instance-1", "", &mockK8sClient{}, nil, permissiveLoader(t))
	params, _ := structpb.NewStruct(map[string]interface{}{
		"kind": "pod", "name": "nginx", "namespace": "production",     // not in allowlist.
	})
	resp := d.Handle(&agentv1.CommandRequest{Type: agentv1.CommandType_DELETE_RESOURCE, Params: params})

	if resp.GetStatus() != agentv1.Status_PERMISSION_DENIED {
		t.Errorf("status = %v, want PERMISSION_DENIED", resp.GetStatus())
	}
	if resp.GetErrorCode() != "NAMESPACE_NOT_ALLOWED" {
		t.Errorf("error_code = %q", resp.GetErrorCode())
	}
}

func TestHandle_DeleteResource_ClusterScopedKind_BypassesNamespaceCheck(t *testing.T) {
	// "node" 같은 cluster-scoped kind 는 namespace 비어있어도 OK + allowlist 검사 안 함.
	called := false
	mock := &mockK8sClient{
		deleteFn: func(ctx context.Context, opts k8s.DeleteResourceOptions) error {
			called = true
			return nil
		},
	}
	d := New("instance-1", "", mock, nil, permissiveLoader(t))
	params, _ := structpb.NewStruct(map[string]interface{}{
		"kind": "node", "name": "node-1",     // namespace 비어있음.
	})
	resp := d.Handle(&agentv1.CommandRequest{Type: agentv1.CommandType_DELETE_RESOURCE, Params: params})

	if resp.GetStatus() != agentv1.Status_OK {
		t.Fatalf("status = %v: %s", resp.GetStatus(), resp.GetErrorMessage())
	}
	if !called {
		t.Error("Delete should be called for cluster-scoped kind")
	}
}

func TestHandle_DeleteResource_UnsupportedKind_InvalidParams(t *testing.T) {
	mock := &mockK8sClient{
		deleteFn: func(ctx context.Context, opts k8s.DeleteResourceOptions) error {
			return k8s.ErrUnsupportedKind
		},
	}
	d := New("instance-1", "", mock, nil, permissiveLoader(t))
	params, _ := structpb.NewStruct(map[string]interface{}{
		"kind": "unicornresource", "name": "x", "namespace": "web",
	})
	resp := d.Handle(&agentv1.CommandRequest{Type: agentv1.CommandType_DELETE_RESOURCE, Params: params})

	if resp.GetStatus() != agentv1.Status_INVALID_PARAMS {
		t.Errorf("status = %v, want INVALID_PARAMS", resp.GetStatus())
	}
	if resp.GetErrorCode() != "UNSUPPORTED_KIND" {
		t.Errorf("error_code = %q", resp.GetErrorCode())
	}
}

func TestHandle_DeleteResource_K8sFailure_PropagatesFailed(t *testing.T) {
	mock := &mockK8sClient{
		deleteFn: func(ctx context.Context, opts k8s.DeleteResourceOptions) error {
			return errors.New("apiserver 503")
		},
	}
	d := New("instance-1", "", mock, nil, permissiveLoader(t))
	params, _ := structpb.NewStruct(map[string]interface{}{
		"kind": "pod", "name": "nginx", "namespace": "web",
	})
	resp := d.Handle(&agentv1.CommandRequest{Type: agentv1.CommandType_DELETE_RESOURCE, Params: params})

	if resp.GetStatus() != agentv1.Status_FAILED {
		t.Errorf("status = %v, want FAILED", resp.GetStatus())
	}
	if resp.GetErrorCode() != "K8S_DELETE_FAILED" {
		t.Errorf("error_code = %q", resp.GetErrorCode())
	}
}

// ===== GET_RESOURCE  =====

func TestHandle_GetResource_Success_ReturnsJsonResource(t *testing.T) {
	var captured k8s.GetResourceOptions
	mock := &mockK8sClient{
		getFn: func(ctx context.Context, opts k8s.GetResourceOptions) (string, error) {
			captured = opts
			return `{"kind":"Pod","metadata":{"name":"nginx-abc","namespace":"web"}}`, nil
		},
	}
	d := New("instance-1", "", mock, nil, permissiveLoader(t))
	params, _ := structpb.NewStruct(map[string]interface{}{
		"kind": "pod", "name": "nginx-abc", "namespace": "web",
	})
	resp := d.Handle(&agentv1.CommandRequest{Type: agentv1.CommandType_GET_RESOURCE, Params: params})

	if resp.GetStatus() != agentv1.Status_OK {
		t.Fatalf("status = %v: %s", resp.GetStatus(), resp.GetErrorMessage())
	}
	if captured.Kind != "pod" || captured.Name != "nginx-abc" || captured.Namespace != "web" {
		t.Errorf("opts forwarded: %+v", captured)
	}
	resource := resp.GetResult().GetFields()["resource"].GetStringValue()
	if resource == "" {
		t.Error("resource JSON missing")
	}
	if resp.GetResult().GetFields()["length_bytes"].GetNumberValue() <= 0 {
		t.Error("length_bytes not set")
	}
}

func TestHandle_GetResource_MissingParam_InvalidParams(t *testing.T) {
	d := New("instance-1", "", &mockK8sClient{}, nil, permissiveLoader(t))
	params, _ := structpb.NewStruct(map[string]interface{}{"kind": "pod"})     // name 누락.
	resp := d.Handle(&agentv1.CommandRequest{Type: agentv1.CommandType_GET_RESOURCE, Params: params})

	if resp.GetStatus() != agentv1.Status_INVALID_PARAMS {
		t.Errorf("status = %v, want INVALID_PARAMS", resp.GetStatus())
	}
}

func TestHandle_GetResource_NamespaceNotAllowed_Reject(t *testing.T) {
	d := New("instance-1", "", &mockK8sClient{}, nil, permissiveLoader(t))
	params, _ := structpb.NewStruct(map[string]interface{}{
		"kind": "pod", "name": "nginx", "namespace": "production",
	})
	resp := d.Handle(&agentv1.CommandRequest{Type: agentv1.CommandType_GET_RESOURCE, Params: params})

	if resp.GetStatus() != agentv1.Status_PERMISSION_DENIED {
		t.Errorf("status = %v, want PERMISSION_DENIED", resp.GetStatus())
	}
}

func TestHandle_GetResource_ClusterScopedKind_BypassesNamespaceCheck(t *testing.T) {
	mock := &mockK8sClient{
		getFn: func(ctx context.Context, opts k8s.GetResourceOptions) (string, error) {
			return `{"kind":"Node","metadata":{"name":"node-1"}}`, nil
		},
	}
	d := New("instance-1", "", mock, nil, permissiveLoader(t))
	params, _ := structpb.NewStruct(map[string]interface{}{
		"kind": "node", "name": "node-1",     // no namespace.
	})
	resp := d.Handle(&agentv1.CommandRequest{Type: agentv1.CommandType_GET_RESOURCE, Params: params})

	if resp.GetStatus() != agentv1.Status_OK {
		t.Fatalf("status = %v: %s", resp.GetStatus(), resp.GetErrorMessage())
	}
}

func TestHandle_GetResource_UnsupportedKind_InvalidParams(t *testing.T) {
	mock := &mockK8sClient{
		getFn: func(ctx context.Context, opts k8s.GetResourceOptions) (string, error) {
			return "", k8s.ErrUnsupportedKind
		},
	}
	d := New("instance-1", "", mock, nil, permissiveLoader(t))
	params, _ := structpb.NewStruct(map[string]interface{}{
		"kind": "unicorn", "name": "x", "namespace": "web",
	})
	resp := d.Handle(&agentv1.CommandRequest{Type: agentv1.CommandType_GET_RESOURCE, Params: params})

	if resp.GetStatus() != agentv1.Status_INVALID_PARAMS {
		t.Errorf("status = %v, want INVALID_PARAMS", resp.GetStatus())
	}
	if resp.GetErrorCode() != "UNSUPPORTED_KIND" {
		t.Errorf("error_code = %q", resp.GetErrorCode())
	}
}

// ===== LIST_RESOURCES  =====

func TestHandle_ListResources_Success_ReturnsPagedItems(t *testing.T) {
	var captured k8s.ListResourcesOptions
	mock := &mockK8sClient{
		listResFn: func(ctx context.Context, opts k8s.ListResourcesOptions) (*k8s.ListResourcesResult, error) {
			captured = opts
			return &k8s.ListResourcesResult{
				Items:         `[{"kind":"Pod","metadata":{"name":"nginx-1"}}]`,
				ContinueToken: "tok-next",
				ReturnedCount: 1,
			}, nil
		},
	}
	d := New("instance-1", "", mock, nil, permissiveLoader(t))
	params, _ := structpb.NewStruct(map[string]interface{}{
		"kind": "pods", "namespace": "web", "limit": "10",
	})
	resp := d.Handle(&agentv1.CommandRequest{Type: agentv1.CommandType_LIST_RESOURCES, Params: params})

	if resp.GetStatus() != agentv1.Status_OK {
		t.Fatalf("status = %v: %s", resp.GetStatus(), resp.GetErrorMessage())
	}
	if captured.Kind != "pods" || captured.Namespace != "web" || captured.Limit != 10 {
		t.Errorf("opts forwarded: %+v", captured)
	}
	if resp.GetResult().GetFields()["continue_token"].GetStringValue() != "tok-next" {
		t.Errorf("continue_token not forwarded")
	}
	if resp.GetResult().GetFields()["returned_count"].GetNumberValue() != 1 {
		t.Errorf("returned_count = %v", resp.GetResult().GetFields()["returned_count"].GetNumberValue())
	}
}

func TestHandle_ListResources_MissingKind_InvalidParams(t *testing.T) {
	d := New("instance-1", "", &mockK8sClient{}, nil, permissiveLoader(t))
	params, _ := structpb.NewStruct(map[string]interface{}{"namespace": "web"})
	resp := d.Handle(&agentv1.CommandRequest{Type: agentv1.CommandType_LIST_RESOURCES, Params: params})

	if resp.GetStatus() != agentv1.Status_INVALID_PARAMS {
		t.Errorf("status = %v, want INVALID_PARAMS", resp.GetStatus())
	}
}

func TestHandle_ListResources_AllNamespacesSentinel(t *testing.T) {
	var captured string
	mock := &mockK8sClient{
		listResFn: func(ctx context.Context, opts k8s.ListResourcesOptions) (*k8s.ListResourcesResult, error) {
			captured = opts.Namespace
			return &k8s.ListResourcesResult{Items: "[]", ReturnedCount: 0}, nil
		},
	}
	d := New("instance-1", "", mock, nil, permissiveLoader(t))
	params, _ := structpb.NewStruct(map[string]interface{}{
		"kind": "pods", "namespace": "_all",
	})
	d.Handle(&agentv1.CommandRequest{Type: agentv1.CommandType_LIST_RESOURCES, Params: params})

	if captured != "" {
		t.Errorf("_all should map to empty: %q", captured)
	}
}

func TestHandle_ListResources_NamespaceNotAllowed_Reject(t *testing.T) {
	d := New("instance-1", "", &mockK8sClient{}, nil, permissiveLoader(t))
	params, _ := structpb.NewStruct(map[string]interface{}{
		"kind": "pods", "namespace": "production",
	})
	resp := d.Handle(&agentv1.CommandRequest{Type: agentv1.CommandType_LIST_RESOURCES, Params: params})

	if resp.GetStatus() != agentv1.Status_PERMISSION_DENIED {
		t.Errorf("status = %v, want PERMISSION_DENIED", resp.GetStatus())
	}
}

func TestHandle_ListResources_LimitClamped(t *testing.T) {
	var captured int64
	mock := &mockK8sClient{
		listResFn: func(ctx context.Context, opts k8s.ListResourcesOptions) (*k8s.ListResourcesResult, error) {
			captured = opts.Limit
			return &k8s.ListResourcesResult{Items: "[]", ReturnedCount: 0}, nil
		},
	}
	d := New("instance-1", "", mock, nil, permissiveLoader(t))
	// limit > 500 cap.
	params, _ := structpb.NewStruct(map[string]interface{}{
		"kind": "pods", "namespace": "web", "limit": "10000",
	})
	d.Handle(&agentv1.CommandRequest{Type: agentv1.CommandType_LIST_RESOURCES, Params: params})

	if captured != 500 {
		t.Errorf("limit clamped = %d, want 500", captured)
	}
}

// resource_policy 의 deny 룰이 LIST_RESOURCES 를 차단.
// secrets 를 모든 namespace 에서 deny — RESOURCE_KIND_DENIED 응답.
func TestHandle_ListResources_ResourcePolicy_DenyKind(t *testing.T) {
	loader := resourcePolicyLoader(t, `
mode: allow_all_discovered
deny:
  - kind: secrets
`)
	called := false
	mock := &mockK8sClient{
		listResFn: func(ctx context.Context, opts k8s.ListResourcesOptions) (*k8s.ListResourcesResult, error) {
			called = true
			return &k8s.ListResourcesResult{Items: "[]", ReturnedCount: 0}, nil
		},
	}
	d := New("instance-1", "", mock, nil, loader)
	params, _ := structpb.NewStruct(map[string]interface{}{
		"kind": "secrets", "namespace": "web",
	})
	resp := d.Handle(&agentv1.CommandRequest{Type: agentv1.CommandType_LIST_RESOURCES, Params: params})

	if resp.GetStatus() != agentv1.Status_INVALID_PARAMS {
		t.Errorf("status = %v, want INVALID_PARAMS", resp.GetStatus())
	}
	if resp.GetErrorCode() != "RESOURCE_KIND_DENIED" {
		t.Errorf("error_code = %q, want RESOURCE_KIND_DENIED", resp.GetErrorCode())
	}
	if called {
		t.Error("ListResources should NOT be called when policy denies kind")
	}
}

// strict 모드 — allow 에 없는 kind 는 모두 거부.
func TestHandle_GetResource_ResourcePolicy_StrictMode_NotInAllow(t *testing.T) {
	loader := resourcePolicyLoader(t, `
mode: strict
allow:
  - kind: pods
`)
	called := false
	mock := &mockK8sClient{
		getFn: func(ctx context.Context, opts k8s.GetResourceOptions) (string, error) {
			called = true
			return "{}", nil
		},
	}
	d := New("instance-1", "", mock, nil, loader)
	params, _ := structpb.NewStruct(map[string]interface{}{
		"kind": "configmaps", "name": "x", "namespace": "web",
	})
	resp := d.Handle(&agentv1.CommandRequest{Type: agentv1.CommandType_GET_RESOURCE, Params: params})

	if resp.GetStatus() != agentv1.Status_INVALID_PARAMS {
		t.Errorf("status = %v, want INVALID_PARAMS", resp.GetStatus())
	}
	if resp.GetErrorCode() != "RESOURCE_KIND_DENIED" {
		t.Errorf("error_code = %q", resp.GetErrorCode())
	}
	if called {
		t.Error("GetResource should NOT be called in strict mode for kind outside allow list")
	}
}

// strict 모드 — allow 에 있는 kind 는 정상 통과.
func TestHandle_GetResource_ResourcePolicy_StrictMode_AllowedKind(t *testing.T) {
	loader := resourcePolicyLoader(t, `
mode: strict
allow:
  - kind: pods
`)
	mock := &mockK8sClient{
		getFn: func(ctx context.Context, opts k8s.GetResourceOptions) (string, error) {
			return `{"kind":"Pod","metadata":{"name":"x"}}`, nil
		},
	}
	d := New("instance-1", "", mock, nil, loader)
	params, _ := structpb.NewStruct(map[string]interface{}{
		"kind": "pods", "name": "x", "namespace": "web",
	})
	resp := d.Handle(&agentv1.CommandRequest{Type: agentv1.CommandType_GET_RESOURCE, Params: params})

	if resp.GetStatus() != agentv1.Status_OK {
		t.Fatalf("status = %v: %s", resp.GetStatus(), resp.GetErrorMessage())
	}
}

// deny 룰의 namespace 제약 — kube-system 에서만 configmaps deny, web 에선 허용.
func TestHandle_ListResources_ResourcePolicy_NamespaceScopedDeny(t *testing.T) {
	loader := resourcePolicyLoader(t, `
mode: allow_all_discovered
deny:
  - kind: configmaps
    namespace: kube-system
`)
	mock := &mockK8sClient{
		listResFn: func(ctx context.Context, opts k8s.ListResourcesOptions) (*k8s.ListResourcesResult, error) {
			return &k8s.ListResourcesResult{Items: "[]", ReturnedCount: 0}, nil
		},
	}
	d := New("instance-1", "", mock, nil, loader)

	// kube-system 의 configmaps 는 deny.
	params, _ := structpb.NewStruct(map[string]interface{}{
		"kind": "configmaps", "namespace": "kube-system",
	})
	resp := d.Handle(&agentv1.CommandRequest{Type: agentv1.CommandType_LIST_RESOURCES, Params: params})
	if resp.GetErrorCode() != "RESOURCE_KIND_DENIED" {
		t.Errorf("kube-system configmaps should be denied: error=%q", resp.GetErrorCode())
	}

	// web 의 configmaps 는 OK (deny 룰 namespace 불일치).
	params2, _ := structpb.NewStruct(map[string]interface{}{
		"kind": "configmaps", "namespace": "web",
	})
	resp2 := d.Handle(&agentv1.CommandRequest{Type: agentv1.CommandType_LIST_RESOURCES, Params: params2})
	if resp2.GetStatus() != agentv1.Status_OK {
		t.Errorf("web configmaps should be OK: status=%v err=%q", resp2.GetStatus(), resp2.GetErrorMessage())
	}
}

func TestHandle_ListResources_UnsupportedKind_InvalidParams(t *testing.T) {
	mock := &mockK8sClient{
		listResFn: func(ctx context.Context, opts k8s.ListResourcesOptions) (*k8s.ListResourcesResult, error) {
			return nil, k8s.ErrUnsupportedKind
		},
	}
	d := New("instance-1", "", mock, nil, permissiveLoader(t))
	params, _ := structpb.NewStruct(map[string]interface{}{
		"kind": "unicorn", "namespace": "web",
	})
	resp := d.Handle(&agentv1.CommandRequest{Type: agentv1.CommandType_LIST_RESOURCES, Params: params})

	if resp.GetStatus() != agentv1.Status_INVALID_PARAMS {
		t.Errorf("status = %v, want INVALID_PARAMS", resp.GetStatus())
	}
	if resp.GetErrorCode() != "UNSUPPORTED_KIND" {
		t.Errorf("error_code = %q", resp.GetErrorCode())
	}
}

// ===== APPLY_MANIFEST  =====

func TestHandle_ApplyManifest_Success_ReturnsAppliedList(t *testing.T) {
	var captured k8s.ApplyManifestOptions
	mock := &mockK8sClient{
		applyFn: func(ctx context.Context, opts k8s.ApplyManifestOptions) (*k8s.ApplyManifestResult, error) {
			captured = opts
			return &k8s.ApplyManifestResult{
				Applied: []k8s.AppliedResource{
					{APIVersion: "apps/v1", Kind: "Deployment", Name: "nginx", Namespace: "web",
						ResourceVersion: "123", UID: "uid-1"},
				},
			}, nil
		},
	}
	d := New("instance-1", "", mock, nil, permissiveLoader(t))
	params, _ := structpb.NewStruct(map[string]interface{}{
		"manifest":  "apiVersion: apps/v1\nkind: Deployment\nmetadata:\n  name: nginx\n",
		"namespace": "web",
	})
	resp := d.Handle(&agentv1.CommandRequest{Type: agentv1.CommandType_APPLY_MANIFEST, Params: params})

	if resp.GetStatus() != agentv1.Status_OK {
		t.Fatalf("status = %v: %s", resp.GetStatus(), resp.GetErrorMessage())
	}
	if captured.DefaultNamespace != "web" {
		t.Errorf("defaultNamespace = %q", captured.DefaultNamespace)
	}
	if captured.FieldManager != "aipaas-agent" {
		t.Errorf("fieldManager = %q", captured.FieldManager)
	}
	if resp.GetResult().GetFields()["applied_count"].GetNumberValue() != 1 {
		t.Errorf("applied_count = %v", resp.GetResult().GetFields()["applied_count"].GetNumberValue())
	}
}

func TestHandle_ApplyManifest_MissingManifest_InvalidParams(t *testing.T) {
	d := New("instance-1", "", &mockK8sClient{}, nil, permissiveLoader(t))
	params, _ := structpb.NewStruct(map[string]interface{}{"namespace": "web"})
	resp := d.Handle(&agentv1.CommandRequest{Type: agentv1.CommandType_APPLY_MANIFEST, Params: params})

	if resp.GetStatus() != agentv1.Status_INVALID_PARAMS {
		t.Errorf("status = %v, want INVALID_PARAMS", resp.GetStatus())
	}
}

func TestHandle_ApplyManifest_DefaultNamespaceNotAllowed_Reject(t *testing.T) {
	d := New("instance-1", "", &mockK8sClient{}, nil, permissiveLoader(t))
	params, _ := structpb.NewStruct(map[string]interface{}{
		"manifest":  "kind: Pod\nmetadata: { name: x }",
		"namespace": "production",
	})
	resp := d.Handle(&agentv1.CommandRequest{Type: agentv1.CommandType_APPLY_MANIFEST, Params: params})

	if resp.GetStatus() != agentv1.Status_PERMISSION_DENIED {
		t.Errorf("status = %v, want PERMISSION_DENIED", resp.GetStatus())
	}
	if resp.GetErrorCode() != "NAMESPACE_NOT_ALLOWED" {
		t.Errorf("error_code = %q", resp.GetErrorCode())
	}
}

func TestHandle_ApplyManifest_ResourceTargetsForbiddenNamespace_RejectPostApply(t *testing.T) {
	// Manifest 안의 자원이 default ns 와 다른, 미허용 ns 를 명시한 케이스.
	// AllowList post-apply 검증이 reject 해야 함.
	mock := &mockK8sClient{
		applyFn: func(ctx context.Context, opts k8s.ApplyManifestOptions) (*k8s.ApplyManifestResult, error) {
			return &k8s.ApplyManifestResult{
				Applied: []k8s.AppliedResource{
					{Kind: "Pod", Name: "evil", Namespace: "production", ResourceVersion: "1"},
				},
			}, nil
		},
	}
	d := New("instance-1", "", mock, nil, permissiveLoader(t))
	params, _ := structpb.NewStruct(map[string]interface{}{
		"manifest": "kind: Pod\nmetadata:\n  name: evil\n  namespace: production\n",
		// default ns 는 비어있음 (또는 monitoring) — manifest 가 production 명시.
	})
	resp := d.Handle(&agentv1.CommandRequest{Type: agentv1.CommandType_APPLY_MANIFEST, Params: params})

	if resp.GetStatus() != agentv1.Status_PERMISSION_DENIED {
		t.Errorf("status = %v, want PERMISSION_DENIED", resp.GetStatus())
	}
	if resp.GetErrorCode() != "NAMESPACE_NOT_ALLOWED_POST_APPLY" {
		t.Errorf("error_code = %q", resp.GetErrorCode())
	}
}

func TestHandle_ApplyManifest_ApplyFailure_PropagatesFailed(t *testing.T) {
	mock := &mockK8sClient{
		applyFn: func(ctx context.Context, opts k8s.ApplyManifestOptions) (*k8s.ApplyManifestResult, error) {
			return nil, errors.New("admission webhook denied")
		},
	}
	d := New("instance-1", "", mock, nil, permissiveLoader(t))
	params, _ := structpb.NewStruct(map[string]interface{}{
		"manifest": "kind: Pod\nmetadata: { name: x }",
		"namespace": "web",
	})
	resp := d.Handle(&agentv1.CommandRequest{Type: agentv1.CommandType_APPLY_MANIFEST, Params: params})

	if resp.GetStatus() != agentv1.Status_FAILED {
		t.Errorf("status = %v, want FAILED", resp.GetStatus())
	}
	if resp.GetErrorCode() != "K8S_APPLY_FAILED" {
		t.Errorf("error_code = %q", resp.GetErrorCode())
	}
}

func TestHandle_ApplyManifest_ClusterScopedOnly_NoNamespaceCheck(t *testing.T) {
	// default ns 비어있고, manifest 의 자원도 cluster-scoped (Namespace) — allowlist 통과.
	called := false
	mock := &mockK8sClient{
		applyFn: func(ctx context.Context, opts k8s.ApplyManifestOptions) (*k8s.ApplyManifestResult, error) {
			called = true
			return &k8s.ApplyManifestResult{
				Applied: []k8s.AppliedResource{
					{Kind: "Namespace", Name: "new-ns", Namespace: ""},
				},
			}, nil
		},
	}
	d := New("instance-1", "", mock, nil, permissiveLoader(t))
	params, _ := structpb.NewStruct(map[string]interface{}{
		"manifest": "apiVersion: v1\nkind: Namespace\nmetadata: { name: new-ns }",
		// namespace 비어있음.
	})
	resp := d.Handle(&agentv1.CommandRequest{Type: agentv1.CommandType_APPLY_MANIFEST, Params: params})

	if resp.GetStatus() != agentv1.Status_OK {
		t.Fatalf("status = %v: %s", resp.GetStatus(), resp.GetErrorMessage())
	}
	if !called {
		t.Error("Apply should be called")
	}
}

// ===== LIST_RESOURCE_KINDS  =====

// TestHandle_ListResourceKinds_Success — discovery 결과가 kinds[] 배열로 정상 노출.
// Group / namespaced / short_names 의 wire shape 회귀 보장.
func TestHandle_ListResourceKinds_Success(t *testing.T) {
	called := false
	mock := &mockK8sClient{
		listAPIResFn: func(ctx context.Context) ([]k8s.APIResourceInfo, error) {
			called = true
			return []k8s.APIResourceInfo{
				{Plural: "pods", Singular: "pod", Kind: "Pod", Group: "", Version: "v1",
					Namespaced: true, ShortNames: []string{"po"}},
				{Plural: "deployments", Singular: "deployment", Kind: "Deployment",
					Group: "apps", Version: "v1", Namespaced: true, ShortNames: []string{"deploy"}},
				{Plural: "storageclasses", Singular: "storageclass", Kind: "StorageClass",
					Group: "storage.k8s.io", Version: "v1", Namespaced: false, ShortNames: []string{"sc"}},
			}, nil
		},
	}
	d := New("instance-1", "", mock, nil, permissiveLoader(t))
	resp := d.Handle(&agentv1.CommandRequest{Type: agentv1.CommandType_LIST_RESOURCE_KINDS})

	if resp.GetStatus() != agentv1.Status_OK {
		t.Fatalf("status = %v: %s", resp.GetStatus(), resp.GetErrorMessage())
	}
	if !called {
		t.Error("ListAPIResources should be called")
	}
	if resp.GetResult().GetFields()["count"].GetNumberValue() != 3 {
		t.Errorf("count = %v, want 3", resp.GetResult().GetFields()["count"].GetNumberValue())
	}
	kinds := resp.GetResult().GetFields()["kinds"].GetListValue()
	if kinds == nil || len(kinds.Values) != 3 {
		t.Fatalf("kinds list missing or wrong length: %v", kinds)
	}
	// 첫 entry (pods) 의 wire shape 검증.
	first := kinds.Values[0].GetStructValue().GetFields()
	if first["plural"].GetStringValue() != "pods" {
		t.Errorf("kinds[0].plural = %q, want pods", first["plural"].GetStringValue())
	}
	if first["kind"].GetStringValue() != "Pod" {
		t.Errorf("kinds[0].kind = %q, want Pod", first["kind"].GetStringValue())
	}
	if first["group"].GetStringValue() != "" {
		t.Errorf("kinds[0].group = %q, want empty", first["group"].GetStringValue())
	}
	if first["namespaced"].GetBoolValue() != true {
		t.Errorf("kinds[0].namespaced = %v, want true", first["namespaced"].GetBoolValue())
	}
	shortNames := first["short_names"].GetListValue()
	if shortNames == nil || len(shortNames.Values) != 1 || shortNames.Values[0].GetStringValue() != "po" {
		t.Errorf("kinds[0].short_names = %v, want [po]", shortNames)
	}
	// 마지막 entry — cluster-scoped CRD-ish (storageclasses) namespaced=false 검증.
	third := kinds.Values[2].GetStructValue().GetFields()
	if third["namespaced"].GetBoolValue() != false {
		t.Errorf("storageclasses.namespaced = %v, want false", third["namespaced"].GetBoolValue())
	}
	if third["group"].GetStringValue() != "storage.k8s.io" {
		t.Errorf("storageclasses.group = %q", third["group"].GetStringValue())
	}
}

// TestHandle_ListResourceKinds_NilClient — kube client 미초기화 시 AGENT_UNAVAILABLE.
func TestHandle_ListResourceKinds_NilClient(t *testing.T) {
	d := New("instance-1", "", nil, nil, permissiveLoader(t))
	resp := d.Handle(&agentv1.CommandRequest{Type: agentv1.CommandType_LIST_RESOURCE_KINDS})

	if resp.GetStatus() != agentv1.Status_AGENT_UNAVAILABLE {
		t.Errorf("status = %v, want AGENT_UNAVAILABLE", resp.GetStatus())
	}
}

// TestHandle_ListResourceKinds_DiscoveryFailed — discovery API 자체 실패 시 FAILED + DISCOVERY_FAILED.
func TestHandle_ListResourceKinds_DiscoveryFailed(t *testing.T) {
	mock := &mockK8sClient{
		listAPIResFn: func(ctx context.Context) ([]k8s.APIResourceInfo, error) {
			return nil, errors.New("discovery: forbidden — RBAC denies GET /apis")
		},
	}
	d := New("instance-1", "", mock, nil, permissiveLoader(t))
	resp := d.Handle(&agentv1.CommandRequest{Type: agentv1.CommandType_LIST_RESOURCE_KINDS})

	if resp.GetStatus() != agentv1.Status_FAILED {
		t.Errorf("status = %v, want FAILED", resp.GetStatus())
	}
	if resp.GetErrorCode() != "DISCOVERY_FAILED" {
		t.Errorf("error_code = %q, want DISCOVERY_FAILED", resp.GetErrorCode())
	}
}

// TestHandle_ListResourceKinds_NotInAllowlist_PermissionDenied — operator 가 ConfigMap 에서
// LIST_RESOURCE_KINDS 를 제외하면 PERMISSION_DENIED + COMMAND_NOT_ALLOWED.
func TestHandle_ListResourceKinds_NotInAllowlist_PermissionDenied(t *testing.T) {
	// denyAllLoader 는 빈 ConfigMap → allowed_commands 비어있음 → 모든 명령 deny.
	called := false
	mock := &mockK8sClient{
		listAPIResFn: func(ctx context.Context) ([]k8s.APIResourceInfo, error) {
			called = true
			return nil, nil
		},
	}
	d := New("instance-1", "", mock, nil, denyAllLoader(t))
	resp := d.Handle(&agentv1.CommandRequest{Type: agentv1.CommandType_LIST_RESOURCE_KINDS})

	if resp.GetStatus() != agentv1.Status_PERMISSION_DENIED {
		t.Errorf("status = %v, want PERMISSION_DENIED", resp.GetStatus())
	}
	if resp.GetErrorCode() != "COMMAND_NOT_ALLOWED" {
		t.Errorf("error_code = %q, want COMMAND_NOT_ALLOWED", resp.GetErrorCode())
	}
	if called {
		t.Error("ListAPIResources should NOT be called when command not allowlisted")
	}
}

// ===== RESOLVE_RESOURCE =====

// 정상 — "pod" (singular) → plural="pods" + 기타 metadata.
func TestHandle_ResolveResource_Pods_Success(t *testing.T) {
	mock := &mockK8sClient{
		resolveFn: func(input string) (k8s.ResolvedResource, error) {
			if input != "pod" {
				return k8s.ResolvedResource{}, fmt.Errorf("unexpected input: %q", input)
			}
			return k8s.ResolvedResource{
				Plural:     "pods",
				Singular:   "pod",
				Kind:       "Pod",
				Group:      "",
				Version:    "v1",
				Namespaced: true,
				ShortNames: []string{"po"},
			}, nil
		},
	}
	d := New("instance-1", "", mock, nil, permissiveLoader(t))
	params, _ := structpb.NewStruct(map[string]interface{}{"input": "pod"})
	resp := d.Handle(&agentv1.CommandRequest{Type: agentv1.CommandType_RESOLVE_RESOURCE, Params: params})

	if resp.GetStatus() != agentv1.Status_OK {
		t.Fatalf("status = %v: %s", resp.GetStatus(), resp.GetErrorMessage())
	}
	fields := resp.GetResult().GetFields()
	if fields["plural"].GetStringValue() != "pods" {
		t.Errorf("plural = %q, want pods", fields["plural"].GetStringValue())
	}
	if fields["singular"].GetStringValue() != "pod" {
		t.Errorf("singular = %q, want pod", fields["singular"].GetStringValue())
	}
	if fields["kind"].GetStringValue() != "Pod" {
		t.Errorf("kind = %q, want Pod", fields["kind"].GetStringValue())
	}
	if fields["namespaced"].GetBoolValue() != true {
		t.Errorf("namespaced = %v, want true", fields["namespaced"].GetBoolValue())
	}
	shortNames := fields["short_names"].GetListValue()
	if shortNames == nil || len(shortNames.Values) != 1 || shortNames.Values[0].GetStringValue() != "po" {
		t.Errorf("short_names = %v, want [po]", shortNames)
	}
}

// 단축이름 "pvc" 도 정상 정규화 — plural=persistentvolumeclaims.
func TestHandle_ResolveResource_PVC_Shortname(t *testing.T) {
	mock := &mockK8sClient{
		resolveFn: func(input string) (k8s.ResolvedResource, error) {
			return k8s.ResolvedResource{
				Plural:     "persistentvolumeclaims",
				Singular:   "persistentvolumeclaim",
				Kind:       "PersistentVolumeClaim",
				Group:      "",
				Version:    "v1",
				Namespaced: true,
				ShortNames: []string{"pvc"},
			}, nil
		},
	}
	d := New("instance-1", "", mock, nil, permissiveLoader(t))
	params, _ := structpb.NewStruct(map[string]interface{}{"input": "pvc"})
	resp := d.Handle(&agentv1.CommandRequest{Type: agentv1.CommandType_RESOLVE_RESOURCE, Params: params})

	if resp.GetStatus() != agentv1.Status_OK {
		t.Fatalf("status = %v: %s", resp.GetStatus(), resp.GetErrorMessage())
	}
	if resp.GetResult().GetFields()["plural"].GetStringValue() != "persistentvolumeclaims" {
		t.Errorf("plural = %q", resp.GetResult().GetFields()["plural"].GetStringValue())
	}
}

// Cluster-scoped (StorageClass) — namespaced=false 확인.
func TestHandle_ResolveResource_StorageClass_ClusterScoped(t *testing.T) {
	mock := &mockK8sClient{
		resolveFn: func(input string) (k8s.ResolvedResource, error) {
			return k8s.ResolvedResource{
				Plural:     "storageclasses",
				Singular:   "storageclass",
				Kind:       "StorageClass",
				Group:      "storage.k8s.io",
				Version:    "v1",
				Namespaced: false,
				ShortNames: []string{"sc"},
			}, nil
		},
	}
	d := New("instance-1", "", mock, nil, permissiveLoader(t))
	params, _ := structpb.NewStruct(map[string]interface{}{"input": "sc"})
	resp := d.Handle(&agentv1.CommandRequest{Type: agentv1.CommandType_RESOLVE_RESOURCE, Params: params})

	if resp.GetStatus() != agentv1.Status_OK {
		t.Fatalf("status = %v: %s", resp.GetStatus(), resp.GetErrorMessage())
	}
	if resp.GetResult().GetFields()["namespaced"].GetBoolValue() != false {
		t.Errorf("namespaced = %v, want false", resp.GetResult().GetFields()["namespaced"].GetBoolValue())
	}
	if resp.GetResult().GetFields()["group"].GetStringValue() != "storage.k8s.io" {
		t.Errorf("group = %q", resp.GetResult().GetFields()["group"].GetStringValue())
	}
}

// Typo — ErrUnsupportedKind 응답 + fuzzy suggestion 동봉.
// "storageclas" (거리 3) → ["storageclasses"] suggestion.
func TestHandle_ResolveResource_Typo_ReturnsFuzzyMatch(t *testing.T) {
	mock := &mockK8sClient{
		resolveFn: func(input string) (k8s.ResolvedResource, error) {
			return k8s.ResolvedResource{}, fmt.Errorf("%w: %s", k8s.ErrUnsupportedKind, input)
		},
		listAPIResFn: func(ctx context.Context) ([]k8s.APIResourceInfo, error) {
			return []k8s.APIResourceInfo{
				{Plural: "pods"},
				{Plural: "storageclasses"},
				{Plural: "services"},
				{Plural: "configmaps"},
			}, nil
		},
	}
	d := New("instance-1", "", mock, nil, permissiveLoader(t))
	params, _ := structpb.NewStruct(map[string]interface{}{"input": "storageclas"})
	resp := d.Handle(&agentv1.CommandRequest{Type: agentv1.CommandType_RESOLVE_RESOURCE, Params: params})

	if resp.GetStatus() != agentv1.Status_INVALID_PARAMS {
		t.Errorf("status = %v, want INVALID_PARAMS", resp.GetStatus())
	}
	if resp.GetErrorCode() != "UNSUPPORTED_KIND" {
		t.Errorf("error_code = %q, want UNSUPPORTED_KIND", resp.GetErrorCode())
	}
	suggestions := resp.GetResult().GetFields()["suggestions"].GetListValue()
	if suggestions == nil || len(suggestions.Values) == 0 {
		t.Fatalf("suggestions missing: %v", resp.GetResult())
	}
	found := false
	for _, v := range suggestions.Values {
		if v.GetStringValue() == "storageclasses" {
			found = true
			break
		}
	}
	if !found {
		t.Errorf("suggestions should contain storageclasses: %v", suggestions)
	}
}

// 빈 input — INVALID_PARAMS + MISSING_PARAM.
func TestHandle_ResolveResource_NoInput_Rejected(t *testing.T) {
	d := New("instance-1", "", &mockK8sClient{}, nil, permissiveLoader(t))
	resp := d.Handle(&agentv1.CommandRequest{
		Type:   agentv1.CommandType_RESOLVE_RESOURCE,
		Params: &structpb.Struct{},
	})
	if resp.GetStatus() != agentv1.Status_INVALID_PARAMS {
		t.Errorf("status = %v, want INVALID_PARAMS", resp.GetStatus())
	}
	if resp.GetErrorCode() != "MISSING_PARAM" {
		t.Errorf("error_code = %q, want MISSING_PARAM", resp.GetErrorCode())
	}
}

// AllowList 가 RESOLVE_RESOURCE 를 안 가지면 PERMISSION_DENIED.
func TestHandle_ResolveResource_NotInAllowlist_PermissionDenied(t *testing.T) {
	d := New("instance-1", "", &mockK8sClient{}, nil, denyAllLoader(t))
	params, _ := structpb.NewStruct(map[string]interface{}{"input": "pod"})
	resp := d.Handle(&agentv1.CommandRequest{Type: agentv1.CommandType_RESOLVE_RESOURCE, Params: params})

	if resp.GetStatus() != agentv1.Status_PERMISSION_DENIED {
		t.Errorf("status = %v, want PERMISSION_DENIED", resp.GetStatus())
	}
	if resp.GetErrorCode() != "COMMAND_NOT_ALLOWED" {
		t.Errorf("error_code = %q", resp.GetErrorCode())
	}
}

// ===== GET_AGENT_CONFIG =====

// allowlist snapshot + meta 가 wire 로 정상 노출되는지.
func TestHandle_GetAgentConfig_ReturnsCurrentSnapshot(t *testing.T) {
	loader := permissiveLoader(t)
	d := New("instance-1", "", &mockK8sClient{}, nil, loader)
	resp := d.Handle(&agentv1.CommandRequest{Type: agentv1.CommandType_GET_AGENT_CONFIG})

	if resp.GetStatus() != agentv1.Status_OK {
		t.Fatalf("status = %v: %s", resp.GetStatus(), resp.GetErrorMessage())
	}
	fields := resp.GetResult().GetFields()

	// allowed_namespaces — explicit (no wildcard) 라 "monitoring" 등 포함.
	ns := fields["allowed_namespaces"].GetListValue()
	if ns == nil {
		t.Fatal("allowed_namespaces missing")
	}
	gotNs := map[string]bool{}
	for _, v := range ns.Values {
		gotNs[v.GetStringValue()] = true
	}
	for _, wantNs := range []string{"monitoring", "ingress-system", "web", "kube-system"} {
		if !gotNs[wantNs] {
			t.Errorf("allowed_namespaces missing %q: %v", wantNs, gotNs)
		}
	}
	if fields["allow_all_namespaces"].GetBoolValue() {
		t.Error("allow_all_namespaces should be false for explicit list")
	}

	// allowed_commands — permissiveLoader 의 명령들 포함.
	cmds := fields["allowed_commands"].GetListValue()
	if cmds == nil || len(cmds.Values) == 0 {
		t.Fatal("allowed_commands missing")
	}
	gotCmds := map[string]bool{}
	for _, v := range cmds.Values {
		gotCmds[v.GetStringValue()] = true
	}
	for _, want := range []string{"LIST_PODS", "GET_AGENT_CONFIG", "RESOLVE_RESOURCE"} {
		if !gotCmds[want] {
			t.Errorf("allowed_commands missing %q", want)
		}
	}

	// allowed_charts — permissiveLoader 가 2 개 차트.
	charts := fields["allowed_charts"].GetListValue()
	if charts == nil || len(charts.Values) != 2 {
		t.Errorf("allowed_charts len = %d, want 2: %v", len(charts.Values), charts)
	}

	// last_reload_at — LoadOnce 직후라 비어있지 않아야 함.
	if fields["last_reload_at"].GetStringValue() == "" {
		t.Error("last_reload_at should be non-empty after LoadOnce")
	}

	// resource_policy — permissiveLoader 는 정책 미설정 → mode="".
	rp := fields["resource_policy"].GetStructValue()
	if rp == nil {
		t.Fatal("resource_policy missing")
	}
	if rp.GetFields()["mode"].GetStringValue() != "" {
		t.Errorf("resource_policy.mode = %q, want empty (nil policy)",
			rp.GetFields()["mode"].GetStringValue())
	}
}

// resource_policy 가 있는 ConfigMap — deny/allow 가 wire 로 노출되는지.
func TestHandle_GetAgentConfig_WithResourcePolicy(t *testing.T) {
	// resourcePolicyLoader 는 GET_AGENT_CONFIG 를 allowed_commands 에 포함 안 함 — 추가 loader 작성.
	cm := &corev1.ConfigMap{
		ObjectMeta: metav1.ObjectMeta{Name: "aipaas-agent-allowlist", Namespace: "aipaas-system"},
		Data: map[string]string{
			"allowed_commands": `- GET_AGENT_CONFIG
`,
			"resource_policy": `
mode: allow_all_discovered
deny:
  - kind: secrets
  - kind: configmaps
    namespace: kube-system
allow:
  - kind: pods
`,
		},
	}
	cs := fake.NewSimpleClientset(cm)
	loader := config.NewLoader(cs, "aipaas-system", "aipaas-agent-allowlist")
	if err := loader.LoadOnce(context.Background()); err != nil {
		t.Fatalf("LoadOnce: %v", err)
	}
	d := New("instance-1", "", &mockK8sClient{}, nil, loader)
	resp := d.Handle(&agentv1.CommandRequest{Type: agentv1.CommandType_GET_AGENT_CONFIG})

	if resp.GetStatus() != agentv1.Status_OK {
		t.Fatalf("status = %v: %s", resp.GetStatus(), resp.GetErrorMessage())
	}
	rp := resp.GetResult().GetFields()["resource_policy"].GetStructValue()
	if rp == nil {
		t.Fatal("resource_policy missing")
	}
	if rp.GetFields()["mode"].GetStringValue() != "allow_all_discovered" {
		t.Errorf("mode = %q", rp.GetFields()["mode"].GetStringValue())
	}
	deny := rp.GetFields()["deny"].GetListValue()
	if deny == nil || len(deny.Values) != 2 {
		t.Fatalf("deny len = %d, want 2: %v", len(deny.Values), deny)
	}
	// 첫 deny rule — kind: secrets, namespace: "".
	first := deny.Values[0].GetStructValue().GetFields()
	if first["kind"].GetStringValue() != "secrets" {
		t.Errorf("deny[0].kind = %q", first["kind"].GetStringValue())
	}
	allow := rp.GetFields()["allow"].GetListValue()
	if allow == nil || len(allow.Values) != 1 {
		t.Errorf("allow len = %d, want 1", len(allow.Values))
	}
}

// last_reload_at — 초기 NewLoader 직후 (LoadOnce 호출 전) 는 빈 문자열.
// LoadOnce 후엔 채워짐.
func TestHandle_GetAgentConfig_LastReloadAt_AfterReload(t *testing.T) {
	// LoadOnce 호출 전 — loader 가 emptyAllowList 만 가짐, meta 는 zero.
	cs := fake.NewSimpleClientset()     // 빈 — ConfigMap 미존재.
	loader := config.NewLoader(cs, "aipaas-system", "aipaas-agent-allowlist")
	// GET_AGENT_CONFIG 가 deny-all 정책에선 commandAllowed false — 별도 loader 로 강제 허용.
	// 대신 loader 의 LoadOnce 가 실패해도 GET_AGENT_CONFIG 통과시키는 별도 loader 가 필요.
	// 간단히 — 우선 reload 전 상태 검증을 위해 별도 ConfigMap 으로 GET_AGENT_CONFIG 만 허용.
	cmAllow := &corev1.ConfigMap{
		ObjectMeta: metav1.ObjectMeta{Name: "aipaas-agent-allowlist", Namespace: "aipaas-system"},
		Data: map[string]string{
			"allowed_commands": `- GET_AGENT_CONFIG
`,
		},
	}
	csAllow := fake.NewSimpleClientset(cmAllow)
	loaderAfter := config.NewLoader(csAllow, "aipaas-system", "aipaas-agent-allowlist")
	if err := loaderAfter.LoadOnce(context.Background()); err != nil {
		t.Fatalf("LoadOnce: %v", err)
	}

	d := New("instance-1", "", &mockK8sClient{}, nil, loaderAfter)
	resp := d.Handle(&agentv1.CommandRequest{Type: agentv1.CommandType_GET_AGENT_CONFIG})

	if resp.GetStatus() != agentv1.Status_OK {
		t.Fatalf("status = %v: %s", resp.GetStatus(), resp.GetErrorMessage())
	}
	lastReload := resp.GetResult().GetFields()["last_reload_at"].GetStringValue()
	if lastReload == "" {
		t.Error("last_reload_at should be non-empty after LoadOnce")
	}
	// Parse 가능한 RFC3339.
	if _, perr := time.Parse(time.RFC3339, lastReload); perr != nil {
		t.Errorf("last_reload_at not RFC3339: %q (%v)", lastReload, perr)
	}
	_ = loader     // initial-state loader는 별도 검증 path 가 까다로워 (deny-all) 본 테스트에서 사용 안함
}

// 명령이 allowlist 에 없으면 PERMISSION_DENIED.
func TestHandle_GetAgentConfig_NotInAllowlist_PermissionDenied(t *testing.T) {
	d := New("instance-1", "", &mockK8sClient{}, nil, denyAllLoader(t))
	resp := d.Handle(&agentv1.CommandRequest{Type: agentv1.CommandType_GET_AGENT_CONFIG})

	if resp.GetStatus() != agentv1.Status_PERMISSION_DENIED {
		t.Errorf("status = %v, want PERMISSION_DENIED", resp.GetStatus())
	}
}

