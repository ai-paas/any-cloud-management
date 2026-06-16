// Package helm — agent 의 Helm SDK wrapping.
//
// AllowList 검증은 dispatcher 에서 수행 — 본 package 는 "통과된" 명령만 실행.
//
// 동작 모드:
//   - In-cluster: kube config 를 cluster ServiceAccount token 으로 생성
//   - Out-of-cluster (dev): KUBECONFIG env 또는 ~/.kube/config
package helm

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"time"

	"helm.sh/helm/v3/pkg/action"
	"helm.sh/helm/v3/pkg/chart/loader"
	"helm.sh/helm/v3/pkg/cli"
	"helm.sh/helm/v3/pkg/getter"
	"helm.sh/helm/v3/pkg/release"
	"helm.sh/helm/v3/pkg/repo"
	"k8s.io/cli-runtime/pkg/genericclioptions"
)

// Client — agent 가 사용하는 helm 명령 surface.
type Client interface {
	// Install — chart 를 namespace 에 설치. values 는 string→any map (helm Values 와 동일).
	Install(ctx context.Context, opts InstallOptions) (*Release, error)

	// Uninstall — release 제거. release 가 없으면 NotFoundError. opts 의 KeepHistory / Wait 가
	// helm CLI 의 --keep-history / --wait 옵션 등가.
	Uninstall(ctx context.Context, opts UninstallOptions) error

	// List — namespace 의 모든 release. namespace 빈 문자열이면 all-namespaces.
	List(ctx context.Context, namespace string) ([]Release, error)

	// Status — 단일 release 의 현재 상태. helm CLI `helm status` 등가. release 가 없으면 NotFound.
	Status(ctx context.Context, namespace, releaseName string) (*Release, error)

	// History — release 의 revision 이력. max <= 0 이면 helm 기본 (10). release 미존재면 NotFound.
	History(ctx context.Context, namespace, releaseName string, max int) ([]HistoryRevision, error)

	// Rollback — release 를 지정 revision 으로 복원. revision == 0 이면 직전 성공 revision.
	// rollback 후 status 호출 결과를 같이 반환 (caller 가 2 RTT 없이 한 번에 처리).
	Rollback(ctx context.Context, opts RollbackOptions) (*Release, error)

	// Upgrade — 기존 release 를 새 chart version / values 로 업그레이드.
	// helm SDK 의 action.NewUpgrade 위임. install 과 같은 release lock 공유 (dispatcher 측).
	// release 가 미존재면 NotFoundError 와 유사 형태로 반환 — caller 는 먼저 install 안내.
	Upgrade(ctx context.Context, opts UpgradeOptions) (*Release, error)

	// Settings — helm SDK 의 EnvSettings 반환. 외부에서 RepositoryConfig path / cache 등 접근
	// 필요 시 사용. SyncRepositories 가 사용.
	Settings() *cli.EnvSettings
}

// UpgradeOptions — Helm upgrade 파라미터. InstallOptions 와 거의 동일하지만 atomic /
// reuseValues / resetValues 가 upgrade 특화.
type UpgradeOptions struct {
	ReleaseName    string
	Namespace      string
	Repo           string
	RepoURL        string
	LocalChartPath string
	Chart          string
	Version        string
	Values         map[string]interface{}
	Timeout        time.Duration

	// Atomic — 실패 시 자동 rollback. helm CLI 의 --atomic.
	Atomic bool

	// ReuseValues — 기존 release 의 values 보존 + 새 values merge. helm CLI 의 --reuse-values.
	// false 이면 새 values 만 사용 (기존 values 폐기).
	ReuseValues bool

	// ResetValues — 기존 values 모두 reset (chart default 만). helm CLI 의 --reset-values.
	// ReuseValues / ResetValues mutually exclusive — true 이면 ResetValues 우선.
	ResetValues bool

	// MaxHistory — revision 보관 개수. 0 이면 helm 기본 (10).
	MaxHistory int
}

// HistoryRevision — single revision of a release (helm history -o json 의 한 element 등가).
type HistoryRevision struct {
	Revision    int
	Updated     time.Time
	Status      string
	Chart       string
	AppVersion  string
	Description string
}

// RollbackOptions — release rollback 파라미터.
type RollbackOptions struct {
	ReleaseName string
	Namespace   string
	Revision    int // 0 = 직전 성공 revision (helm 기본 동작)
	Wait        bool
	Timeout     time.Duration
}

// UninstallOptions — backend 의 ChartService.uninstallRelease 와 1:1 매핑.
// 빈 Timeout 은 5분 default (chart hook 으로 분 단위 가능성 cushion).
type UninstallOptions struct {
	ReleaseName string
	Namespace   string
	KeepHistory bool // true → revision history 보존 (CLI: --keep-history)
	Wait        bool // true → 모든 자원 deletion 완료까지 대기 (CLI: --wait)
	Timeout     time.Duration
}

// InstallOptions — minimal surface. CreateNamespace 는 installer SA 권한 필요.
type InstallOptions struct {
	ReleaseName string
	Namespace   string
	Repo        string // chart repo alias (예: prometheus-community). 보통 backend 의 ConfigMap 에
	// 등록된 이름. RepoURL 이 명시되면 alias 해상 우회.
	// backend 의 helm_repo table 에서 lookup 한 URL. 명시 시 alias 검색 건너뛰고
	// 직접 chart 다운로드. chart-museum-external 같이 agent 의 ~/.config/helm/repositories.yaml
	// 에 helm-add 안 된 repo 도 정상 동작. 비어 있으면 기존 alias resolve fallback.
	RepoURL string
	// backend 가 pre-fetch 해 push 한 chart .tgz 의 local 경로. 명시 시 URL 다운로드
	// 완전 우회 (agent 가 chartmuseum 등 외부 네트워크 접근 불요). 1순위 path.
	LocalChartPath  string
	Chart           string                 // 예: kube-prometheus-stack
	Version         string                 // 빈 문자열이면 latest (그러나 AllowList 통과 못 함 — 빈 안 됨)
	Values          map[string]interface{} // helm chart values
	CreateNamespace bool
	Timeout         time.Duration
}

// Release — Helm release 의 summary 표현.
type Release struct {
	Name       string
	Namespace  string
	Chart      string
	Version    string     // chart version
	AppVersion string
	Revision   int
	Status     string     // deployed / failed / pending-install 등
	Updated    time.Time
}

// helmClient — production 구현. RESTClientGetter 는 cluster connection 정보.
//
// actionConfig 는 function field 로 분리되어 있어 test 에서 in-memory `storage.Memory` +
// fake `PrintingKubeClient` 를 주입한 *action.Configuration 을 반환하는 fake factory 를 set 해
// install/uninstall/list/status/history/rollback/upgrade 의 e2e 동작을 K8s 의존 없이 검증.
// production 에서는 default factory (NewClient 가 set) 가 c.actionConfigReal 호출.
type helmClient struct {
	restGetter genericclioptions.RESTClientGetter
	settings   *cli.EnvSettings

	// testable seam. nil 이면 actionConfigReal 사용 (production default).
	actionConfigFactory func(namespace string) (*action.Configuration, error)
}

// NewClient — k8s.Client 가 사용하는 rest.Config 를 그대로 받아 helm 의 RESTClientGetter 로 변환.
// in-cluster restConfig 가 있으면 그것, 없으면 KubeConfigFlags 가 KUBECONFIG fallback.
func NewClient(restConfigGetter genericclioptions.RESTClientGetter) Client {
	settings := cli.New()     // helm 의 env 설정 (HELM_REPOSITORY_CONFIG 등).
	return &helmClient{
		restGetter: restConfigGetter,
		settings:   settings,
	}
}

// NewClientFromKubeFlags — KUBECONFIG 또는 in-cluster auto-detect 의 가장 단순한 entry.
// 본 agent 는 in-cluster 이므로 actual deployment 에서는 settings.RESTClientGetter() 가 적절.
func NewClientFromKubeFlags(kubeconfigPath string) Client {
	settings := cli.New()
	if kubeconfigPath != "" {
		settings.KubeConfig = kubeconfigPath
	}
	return &helmClient{
		restGetter: settings.RESTClientGetter(),
		settings:   settings,
	}
}

// Settings — helm SDK EnvSettings 노출. SyncRepositories 가 RepositoryConfig path 와 cache 사용.
func (c *helmClient) Settings() *cli.EnvSettings {
	return c.settings
}

// actionConfig — factory dispatch. test 에서 set 한 fake factory 우선, 없으면
// production 의 actionConfigReal.
func (c *helmClient) actionConfig(namespace string) (*action.Configuration, error) {
	if c.actionConfigFactory != nil {
		return c.actionConfigFactory(namespace)
	}
	return c.actionConfigReal(namespace)
}

// actionConfigReal — production default. K8s RESTClientGetter 로 cfg.Init.
func (c *helmClient) actionConfigReal(namespace string) (*action.Configuration, error) {
	cfg := new(action.Configuration)
	driver := c.settings.Debug     // helm secrets 드라이버 (default) — 별도 storage backend 설정 안 함.
	logger := func(format string, v ...interface{}) {
		slog.Debug("helm", slog.String("msg", fmt.Sprintf(format, v...)))
	}
	if err := cfg.Init(c.restGetter, namespace, driverEnv(driver), logger); err != nil {
		return nil, fmt.Errorf("helm action config init: %w", err)
	}
	return cfg, nil
}

func driverEnv(debugFlag bool) string {
	// helm 의 secrets driver 가 default. memory/sql 등 envvar 가능.
	_ = debugFlag     // suppress unused warning — debug 로깅에 사용.
	return "secrets"
}

func (c *helmClient) Install(ctx context.Context, opts InstallOptions) (*Release, error) {
	if opts.ReleaseName == "" || opts.Version == "" {
		return nil, errors.New("Install: releaseName/version required")
	}
	// LocalChartPath 가 있으면 chart locator 로 사용 (URL 우회). 없으면 repo+chart 필수.
	if opts.LocalChartPath == "" && (opts.Repo == "" || opts.Chart == "") {
		return nil, errors.New("Install: repo+chart 또는 localChartPath 중 하나 필수")
	}
	cfg, err := c.actionConfig(opts.Namespace)
	if err != nil {
		return nil, err
	}

	install := action.NewInstall(cfg)
	install.ReleaseName = opts.ReleaseName
	install.Namespace = opts.Namespace
	install.Version = opts.Version
	install.CreateNamespace = opts.CreateNamespace
	// Chart locator + RepoURL 우선순위:
	//   1순위: LocalChartPath (backend push) — URL 완전 우회.
	//   2순위: RepoURL 명시 (backend lookup) — alias resolve 우회.
	//   3순위: alias resolve fallback (보통 실패).
	var chartLocator string
	if opts.LocalChartPath != "" {
		chartLocator = opts.LocalChartPath
		install.RepoURL = ""     // no remote — 파일 경로만 사용.
	} else {
		chartLocator = opts.Chart
		if opts.RepoURL != "" {
			install.RepoURL = opts.RepoURL
		} else {
			install.RepoURL = repoURL(c.settings, opts.Repo)
		}
	}
	install.Wait = false      // 비동기 — agent 가 long-blocking install 으로 stream 막지 않도록.
	if opts.Timeout > 0 {
		install.Timeout = opts.Timeout
	} else {
		install.Timeout = 5 * time.Minute
	}

	// Chart locate + load.
	chartPath, err := install.ChartPathOptions.LocateChart(chartLocator, c.settings)
	if err != nil {
		return nil, fmt.Errorf("locate chart %s: %w", chartLocator, err)
	}
	ch, err := loader.Load(chartPath)
	if err != nil {
		return nil, fmt.Errorf("load chart %s: %w", chartPath, err)
	}

	rel, err := install.RunWithContext(ctx, ch, opts.Values)
	if err != nil {
		return nil, fmt.Errorf("install: %w", err)
	}
	return toSummary(rel), nil
}

func (c *helmClient) Uninstall(ctx context.Context, opts UninstallOptions) error {
	if opts.ReleaseName == "" {
		return errors.New("Uninstall: releaseName required")
	}
	cfg, err := c.actionConfig(opts.Namespace)
	if err != nil {
		return err
	}
	un := action.NewUninstall(cfg)
	un.KeepHistory = opts.KeepHistory
	un.Wait = opts.Wait
	if opts.Timeout > 0 {
		un.Timeout = opts.Timeout
	} else {
		un.Timeout = 5 * time.Minute
	}
	_, err = un.Run(opts.ReleaseName)
	if err != nil {
		return fmt.Errorf("uninstall %s: %w", opts.ReleaseName, err)
	}
	return nil
}

func (c *helmClient) List(ctx context.Context, namespace string) ([]Release, error) {
	cfg, err := c.actionConfig(namespace)
	if err != nil {
		return nil, err
	}
	list := action.NewList(cfg)
	list.AllNamespaces = namespace == ""
	results, err := list.Run()
	if err != nil {
		return nil, fmt.Errorf("list: %w", err)
	}
	out := make([]Release, 0, len(results))
	for _, r := range results {
		out = append(out, *toSummary(r))
	}
	return out, nil
}

// Status — helm SDK 의 action.NewStatus 호출. release 가 없으면 driver.ErrReleaseNotFound 가
// 그대로 wrap 되어 반환 — dispatcher 가 HELM_NOT_FOUND 로 매핑.
func (c *helmClient) Status(ctx context.Context, namespace, releaseName string) (*Release, error) {
	if releaseName == "" {
		return nil, errors.New("Status: releaseName required")
	}
	cfg, err := c.actionConfig(namespace)
	if err != nil {
		return nil, err
	}
	st := action.NewStatus(cfg)
	rel, err := st.Run(releaseName)
	if err != nil {
		return nil, fmt.Errorf("status %s: %w", releaseName, err)
	}
	return toSummary(rel), nil
}

// History — helm SDK action.NewHistory. 최신 revision 이 앞에 오도록 sort.
func (c *helmClient) History(ctx context.Context, namespace, releaseName string, max int) ([]HistoryRevision, error) {
	if releaseName == "" {
		return nil, errors.New("History: releaseName required")
	}
	cfg, err := c.actionConfig(namespace)
	if err != nil {
		return nil, err
	}
	hist := action.NewHistory(cfg)
	if max > 0 {
		hist.Max = max
	}
	rels, err := hist.Run(releaseName)
	if err != nil {
		return nil, fmt.Errorf("history %s: %w", releaseName, err)
	}
	out := make([]HistoryRevision, 0, len(rels))
	for _, r := range rels {
		hr := HistoryRevision{
			Revision: r.Version,
		}
		if r.Info != nil {
			hr.Status = string(r.Info.Status)
			hr.Updated = r.Info.LastDeployed.Time
			hr.Description = r.Info.Description
		}
		if r.Chart != nil && r.Chart.Metadata != nil {
			hr.Chart = fmt.Sprintf("%s-%s", r.Chart.Metadata.Name, r.Chart.Metadata.Version)
			hr.AppVersion = r.Chart.Metadata.AppVersion
		}
		out = append(out, hr)
	}
	return out, nil
}

// Rollback — helm SDK action.NewRollback. revision 0 이면 helm 의 default (직전 성공 revision).
// rollback 자체는 응답 본문이 없으므로 후속 Status 호출로 결과 보완.
func (c *helmClient) Rollback(ctx context.Context, opts RollbackOptions) (*Release, error) {
	if opts.ReleaseName == "" {
		return nil, errors.New("Rollback: releaseName required")
	}
	cfg, err := c.actionConfig(opts.Namespace)
	if err != nil {
		return nil, err
	}
	rb := action.NewRollback(cfg)
	rb.Version = opts.Revision
	rb.Wait = opts.Wait
	if opts.Timeout > 0 {
		rb.Timeout = opts.Timeout
	} else {
		rb.Timeout = 5 * time.Minute
	}
	if err := rb.Run(opts.ReleaseName); err != nil {
		return nil, fmt.Errorf("rollback %s: %w", opts.ReleaseName, err)
	}
	// rollback 후 status — caller 가 결과를 즉시 확인할 수 있도록.
	return c.Status(ctx, opts.Namespace, opts.ReleaseName)
}

// Upgrade — helm SDK action.NewUpgrade 위임.
// release 가 미존재면 helm SDK 가 "release not found" error 반환 — caller 가 분류.
func (c *helmClient) Upgrade(ctx context.Context, opts UpgradeOptions) (*Release, error) {
	if opts.ReleaseName == "" || opts.Version == "" {
		return nil, errors.New("Upgrade: releaseName/version required")
	}
	if opts.LocalChartPath == "" && (opts.Repo == "" || opts.Chart == "") {
		return nil, errors.New("Upgrade: repo+chart 또는 localChartPath 중 하나 필수")
	}
	cfg, err := c.actionConfig(opts.Namespace)
	if err != nil {
		return nil, err
	}

	upgrade := action.NewUpgrade(cfg)
	upgrade.Namespace = opts.Namespace
	upgrade.Version = opts.Version
	upgrade.Atomic = opts.Atomic
	// ResetValues / ReuseValues mutually exclusive. ResetValues 가 명시되면 우선.
	if opts.ResetValues {
		upgrade.ResetValues = true
	} else if opts.ReuseValues {
		upgrade.ReuseValues = true
	}
	if opts.MaxHistory > 0 {
		upgrade.MaxHistory = opts.MaxHistory
	}
	upgrade.Wait = false // 비동기 — install 과 동일
	if opts.Timeout > 0 {
		upgrade.Timeout = opts.Timeout
	} else {
		upgrade.Timeout = 5 * time.Minute
	}

	// Chart locator — Install 과 동일 우선순위 (LocalChartPath → RepoURL → alias resolve).
	var chartLocator string
	if opts.LocalChartPath != "" {
		chartLocator = opts.LocalChartPath
		upgrade.RepoURL = ""
	} else {
		chartLocator = opts.Chart
		if opts.RepoURL != "" {
			upgrade.RepoURL = opts.RepoURL
		} else {
			upgrade.RepoURL = repoURL(c.settings, opts.Repo)
		}
	}

	chartPath, err := upgrade.ChartPathOptions.LocateChart(chartLocator, c.settings)
	if err != nil {
		return nil, fmt.Errorf("locate chart %s: %w", chartLocator, err)
	}
	ch, err := loader.Load(chartPath)
	if err != nil {
		return nil, fmt.Errorf("load chart %s: %w", chartPath, err)
	}

	rel, err := upgrade.RunWithContext(ctx, opts.ReleaseName, ch, opts.Values)
	if err != nil {
		return nil, fmt.Errorf("upgrade: %w", err)
	}
	return toSummary(rel), nil
}

// repoURL — alias (예: "prometheus-community") 인지 URL 인지 판단. URL 이면 그대로, alias 면 helm
// repo file 에서 resolve.
func repoURL(settings *cli.EnvSettings, raw string) string {
	if startsWithScheme(raw) {
		return raw
	}
	// Repo file 에서 alias resolve.
	rf, err := repo.LoadFile(settings.RepositoryConfig)
	if err != nil {
		return raw     // best-effort — Install 이 chart locate 단계에서 실패.
	}
	for _, e := range rf.Repositories {
		if e.Name == raw {
			return e.URL
		}
	}
	return raw
}

func startsWithScheme(s string) bool {
	for _, scheme := range []string{"http://", "https://", "oci://"} {
		if len(s) >= len(scheme) && s[:len(scheme)] == scheme {
			return true
		}
	}
	return false
}

func toSummary(r *release.Release) *Release {
	if r == nil {
		return nil
	}
	rel := &Release{
		Name:      r.Name,
		Namespace: r.Namespace,
		Revision:  r.Version,
		Status:    string(r.Info.Status),
	}
	if r.Chart != nil && r.Chart.Metadata != nil {
		rel.Chart = r.Chart.Metadata.Name
		rel.Version = r.Chart.Metadata.Version
		rel.AppVersion = r.Chart.Metadata.AppVersion
	}
	if !r.Info.LastDeployed.IsZero() {
		rel.Updated = r.Info.LastDeployed.Time
	}
	return rel
}

// ErrNotFound — release 가 없는 경우.
var ErrNotFound = errors.New("release not found")

// helm 의 default getter providers — chart repo download 에 사용 (init 안 하면 nil).
var _ = getter.Providers{}     // import 유지.
