// helm/client.go 의 testable seam (actionConfigFactory) 와 helm SDK lifecycle 검증.
//
// 전략: in-memory storage.Memory + fake PrintingKubeClient 로 fake *action.Configuration 을 만들어
// actionConfigFactory 로 주입. 그 위에서 helm SDK 의 action.Install/Uninstall/Status/Rollback/Upgrade
// 가 real K8s 없이 lifecycle 을 형성하는지 검증. helmClient 의 public method (Install / Upgrade)
// 자체는 chart.tgz 파일 의존 (chart locator) 이라 unit test scope 밖 — actionConfig seam 만 검증.
package helm

import (
	"context"
	"io"
	"testing"
	"time"

	"helm.sh/helm/v3/pkg/action"
	"helm.sh/helm/v3/pkg/chart"
	chartutil "helm.sh/helm/v3/pkg/chartutil"
	"helm.sh/helm/v3/pkg/cli"
	kubefake "helm.sh/helm/v3/pkg/kube/fake"
	"helm.sh/helm/v3/pkg/release"
	"helm.sh/helm/v3/pkg/storage"
	"helm.sh/helm/v3/pkg/storage/driver"
)

// newTestClient — actionConfigFactory 를 fake 로 set 한 helmClient. storage 는 caller 가 보유 / 검증.
//
// settings 도 cli.New() — chart locator 가 LocalChartPath 파일 경로 resolve 시 필요. test 에서
// LocalChartPath 로 디스크에 쓴 chart 를 load 하는 path 도 cover.
func newTestClient(t *testing.T) (*helmClient, *storage.Storage) {
	t.Helper()
	store := storage.Init(driver.NewMemory())
	c := &helmClient{
		settings: cli.New(),
		actionConfigFactory: func(namespace string) (*action.Configuration, error) {
			return &action.Configuration{
				Releases:     store,
				KubeClient:   &kubefake.PrintingKubeClient{Out: io.Discard},
				Capabilities: chartutil.DefaultCapabilities,
				Log:          func(format string, args ...interface{}) { t.Logf(format, args...) },
			}, nil
		},
	}
	return c, store
}

// writeChartToTmp — chart.Chart 를 디스크에 .tgz 로 저장 후 그 경로 반환. helmClient.Install 의
// LocalChartPath 흐름을 검증할 때 사용. t.TempDir() 가 정리 보장.
func writeChartToTmp(t *testing.T, ch *chart.Chart) string {
	t.Helper()
	dir := t.TempDir()
	tgzPath, err := chartutil.Save(ch, dir)
	if err != nil {
		t.Fatalf("chartutil.Save: %v", err)
	}
	return tgzPath
}

// minimalChart — 빈 manifest 의 dummy chart. K8s apply 가 일어나도 fake KubeClient 가 noop.
func minimalChart(name, version string) *chart.Chart {
	return &chart.Chart{
		Metadata: &chart.Metadata{
			APIVersion: chart.APIVersionV2,
			Name:       name,
			Version:    version,
			Type:       "application",
		},
	}
}

// installViaSDK — actionConfig 의 fake 를 직접 사용해 release 1 개 install.
// helmClient.Install 은 chart .tgz 파일 의존이라 unit test 에선 SDK 직접 호출.
func installViaSDK(t *testing.T, c *helmClient, name, namespace, version string) *release.Release {
	t.Helper()
	cfg, err := c.actionConfig(namespace)
	if err != nil {
		t.Fatalf("actionConfig: %v", err)
	}
	install := action.NewInstall(cfg)
	install.ReleaseName = name
	install.Namespace = namespace
	rel, err := install.RunWithContext(context.Background(), minimalChart(name, version), map[string]interface{}{})
	if err != nil {
		t.Fatalf("SDK install: %v", err)
	}
	return rel
}

// --------------------------------------------------------------------------
// Seam 검증 — actionConfigFactory dispatch
// --------------------------------------------------------------------------

func TestActionConfig_UsesFakeFactoryWhenSet(t *testing.T) {
	c, _ := newTestClient(t)
	cfg, err := c.actionConfig("any-ns")
	if err != nil {
		t.Fatalf("actionConfig: %v", err)
	}
	if cfg.Releases == nil || cfg.KubeClient == nil {
		t.Errorf("fake actionConfig should have Releases + KubeClient set")
	}
}

func TestActionConfig_FactoryReceivesNamespace(t *testing.T) {
	gotNs := ""
	c := &helmClient{
		actionConfigFactory: func(namespace string) (*action.Configuration, error) {
			gotNs = namespace
			return &action.Configuration{
				Releases:     storage.Init(driver.NewMemory()),
				KubeClient:   &kubefake.PrintingKubeClient{Out: io.Discard},
				Capabilities: chartutil.DefaultCapabilities,
			}, nil
		},
	}
	_, _ = c.actionConfig("ns-xyz")
	if gotNs != "ns-xyz" {
		t.Errorf("expected factory called with ns=ns-xyz, got %q", gotNs)
	}
}

// --------------------------------------------------------------------------
// SDK lifecycle on fake config — production path 의 hot 영역과 동일 SDK 호출
// --------------------------------------------------------------------------

func TestSDK_InstallThenStatus_GoldenPath(t *testing.T) {
	c, store := newTestClient(t)
	installViaSDK(t, c, "alpha", "default", "0.1.0")

	cfg, _ := c.actionConfig("default")
	status := action.NewStatus(cfg)
	rel, err := status.Run("alpha")
	if err != nil {
		t.Fatalf("Status: %v", err)
	}
	if rel.Info.Status != release.StatusDeployed {
		t.Errorf("expected status=deployed, got %s", rel.Info.Status)
	}
	if stored, err := store.Last("alpha"); err != nil || stored == nil {
		t.Errorf("store.Last: err=%v stored=%v", err, stored)
	}
}

func TestSDK_Uninstall_RemovesRelease(t *testing.T) {
	c, store := newTestClient(t)
	installViaSDK(t, c, "beta", "default", "0.1.0")

	cfg, _ := c.actionConfig("default")
	un := action.NewUninstall(cfg)
	if _, err := un.Run("beta"); err != nil {
		t.Fatalf("Uninstall: %v", err)
	}

	// store.History 가 비거나 uninstalled 상태.
	hist, _ := store.History("beta")
	for _, r := range hist {
		if r.Info != nil && r.Info.Status == release.StatusDeployed {
			t.Errorf("release still deployed after uninstall: %+v", r)
		}
	}
}

func TestSDK_UpgradeThenHistory_MultipleRevisions(t *testing.T) {
	c, _ := newTestClient(t)
	installViaSDK(t, c, "gamma", "default", "0.1.0")

	cfg, _ := c.actionConfig("default")
	upgrade := action.NewUpgrade(cfg)
	upgrade.Namespace = "default"
	_, err := upgrade.RunWithContext(context.Background(), "gamma", minimalChart("gamma", "0.2.0"), map[string]interface{}{})
	if err != nil {
		t.Fatalf("Upgrade: %v", err)
	}

	hist := action.NewHistory(cfg)
	hist.Max = 10
	revs, err := hist.Run("gamma")
	if err != nil {
		t.Fatalf("History: %v", err)
	}
	if len(revs) < 2 {
		t.Errorf("expected >=2 revisions after upgrade, got %d", len(revs))
	}
}

func TestSDK_Rollback_AfterUpgrade_RestoresPrevious(t *testing.T) {
	c, _ := newTestClient(t)
	installViaSDK(t, c, "delta", "default", "0.1.0")

	cfg, _ := c.actionConfig("default")
	upgrade := action.NewUpgrade(cfg)
	upgrade.Namespace = "default"
	if _, err := upgrade.RunWithContext(context.Background(), "delta", minimalChart("delta", "0.2.0"), map[string]interface{}{}); err != nil {
		t.Fatalf("setup Upgrade: %v", err)
	}

	rb := action.NewRollback(cfg)
	rb.Version = 1
	if err := rb.Run("delta"); err != nil {
		t.Fatalf("Rollback: %v", err)
	}

	hist := action.NewHistory(cfg)
	hist.Max = 10
	revs, _ := hist.Run("delta")
	// Install + Upgrade + Rollback = 3 revisions.
	if len(revs) < 3 {
		t.Errorf("expected >=3 revisions after rollback, got %d", len(revs))
	}
}

func TestSDK_List_ReturnsAllInstalledInNamespace(t *testing.T) {
	c, _ := newTestClient(t)
	installViaSDK(t, c, "r1", "default", "0.1.0")
	installViaSDK(t, c, "r2", "default", "0.1.0")

	cfg, _ := c.actionConfig("default")
	list := action.NewList(cfg)
	list.AllNamespaces = false
	rels, err := list.Run()
	if err != nil {
		t.Fatalf("List: %v", err)
	}
	if len(rels) < 2 {
		t.Errorf("expected >=2 releases, got %d: %+v", len(rels), rels)
	}
}

// --------------------------------------------------------------------------
// 입력 검증 — helmClient.Install 의 가드 (chart locator 없이도 도달하는 path)
// --------------------------------------------------------------------------

func TestInstall_MissingReleaseName_Error(t *testing.T) {
	c, _ := newTestClient(t)
	_, err := c.Install(context.Background(), InstallOptions{
		ReleaseName: "",
		Version:     "0.1.0",
		Namespace:   "default",
		Repo:        "stable",
		Chart:       "x",
	})
	if err == nil {
		t.Errorf("Install with empty ReleaseName should error")
	}
}

func TestInstall_MissingVersion_Error(t *testing.T) {
	c, _ := newTestClient(t)
	_, err := c.Install(context.Background(), InstallOptions{
		ReleaseName: "x",
		Version:     "",
		Namespace:   "default",
		Repo:        "stable",
		Chart:       "x",
	})
	if err == nil {
		t.Errorf("Install with empty Version should error")
	}
}

func TestInstall_MissingChartLocator_Error(t *testing.T) {
	c, _ := newTestClient(t)
	_, err := c.Install(context.Background(), InstallOptions{
		ReleaseName: "x",
		Version:     "0.1.0",
		Namespace:   "default",
		// Repo / Chart / LocalChartPath 모두 비움 — 검증 path 에서 fail.
	})
	if err == nil {
		t.Errorf("Install with no chart locator should error")
	}
}

// --------------------------------------------------------------------------
// helmClient.Install / Upgrade — LocalChartPath path (chart .tgz 디스크 로드)
// --------------------------------------------------------------------------

func TestInstall_LocalChartPath_GoldenPath(t *testing.T) {
	c, store := newTestClient(t)
	chartPath := writeChartToTmp(t, minimalChart("local-iota", "0.1.0"))

	rel, err := c.Install(context.Background(), InstallOptions{
		ReleaseName:    "iota",
		Version:        "0.1.0",
		Namespace:      "default",
		LocalChartPath: chartPath,
		Values:         map[string]interface{}{"foo": "bar"},
		Timeout:        30 * time.Second,
	})
	if err != nil {
		t.Fatalf("Install LocalChartPath: %v", err)
	}
	if rel == nil || rel.Name != "iota" {
		t.Errorf("expected release.Name=iota, got %+v", rel)
	}
	if stored, err := store.Last("iota"); err != nil || stored == nil {
		t.Errorf("storage.Last after Install: err=%v stored=%v", err, stored)
	}
}

func TestInstall_LocalChartPath_CreateNamespace(t *testing.T) {
	c, _ := newTestClient(t)
	chartPath := writeChartToTmp(t, minimalChart("local-kappa", "0.1.0"))

	rel, err := c.Install(context.Background(), InstallOptions{
		ReleaseName:     "kappa",
		Version:         "0.1.0",
		Namespace:       "test-ns",
		LocalChartPath:  chartPath,
		CreateNamespace: true,
	})
	if err != nil {
		t.Fatalf("Install with CreateNamespace: %v", err)
	}
	if rel == nil {
		t.Errorf("expected release, got nil")
	}
}

func TestUpgrade_LocalChartPath_BumpsRevision(t *testing.T) {
	c, _ := newTestClient(t)
	chartV1 := writeChartToTmp(t, minimalChart("local-lambda", "0.1.0"))
	chartV2 := writeChartToTmp(t, minimalChart("local-lambda", "0.2.0"))

	if _, err := c.Install(context.Background(), InstallOptions{
		ReleaseName:    "lambda",
		Version:        "0.1.0",
		Namespace:      "default",
		LocalChartPath: chartV1,
	}); err != nil {
		t.Fatalf("setup Install: %v", err)
	}

	rel, err := c.Upgrade(context.Background(), UpgradeOptions{
		ReleaseName:    "lambda",
		Version:        "0.2.0",
		Namespace:      "default",
		LocalChartPath: chartV2,
	})
	if err != nil {
		t.Fatalf("Upgrade: %v", err)
	}
	if rel == nil || rel.Revision < 2 {
		t.Errorf("expected revision>=2 after upgrade, got %+v", rel)
	}
}

func TestUninstall_ViaHelmClient_RemovesRelease(t *testing.T) {
	c, store := newTestClient(t)
	installViaSDK(t, c, "mu", "default", "0.1.0")

	if err := c.Uninstall(context.Background(), UninstallOptions{
		ReleaseName: "mu",
		Namespace:   "default",
	}); err != nil {
		t.Fatalf("Uninstall: %v", err)
	}

	// KeepHistory=false (default) — 모두 제거.
	hist, _ := store.History("mu")
	for _, r := range hist {
		if r.Info != nil && r.Info.Status == release.StatusDeployed {
			t.Errorf("still deployed after Uninstall: %+v", r)
		}
	}
}

func TestUninstall_KeepHistory_PreservesUninstalled(t *testing.T) {
	c, store := newTestClient(t)
	installViaSDK(t, c, "nu", "default", "0.1.0")

	if err := c.Uninstall(context.Background(), UninstallOptions{
		ReleaseName: "nu",
		Namespace:   "default",
		KeepHistory: true,
	}); err != nil {
		t.Fatalf("Uninstall: %v", err)
	}

	hist, _ := store.History("nu")
	foundUninstalled := false
	for _, r := range hist {
		if r.Info != nil && r.Info.Status == release.StatusUninstalled {
			foundUninstalled = true
		}
	}
	if !foundUninstalled {
		t.Errorf("expected uninstalled history with KeepHistory=true")
	}
}

func TestStatus_ViaHelmClient_GoldenPath(t *testing.T) {
	c, _ := newTestClient(t)
	installViaSDK(t, c, "xi", "default", "0.1.0")

	rel, err := c.Status(context.Background(), "default", "xi")
	if err != nil {
		t.Fatalf("Status: %v", err)
	}
	if rel == nil || rel.Name != "xi" || rel.Status != "deployed" {
		t.Errorf("expected status=deployed, got %+v", rel)
	}
}

func TestStatus_NotFound_Error(t *testing.T) {
	c, _ := newTestClient(t)
	_, err := c.Status(context.Background(), "default", "ghost")
	if err == nil {
		t.Errorf("Status of non-existent release should error")
	}
}

func TestHistory_ViaHelmClient_ReturnsRevisions(t *testing.T) {
	c, _ := newTestClient(t)
	installViaSDK(t, c, "omicron", "default", "0.1.0")

	revs, err := c.History(context.Background(), "default", "omicron", 10)
	if err != nil {
		t.Fatalf("History: %v", err)
	}
	if len(revs) < 1 {
		t.Errorf("expected >=1 revision, got %d", len(revs))
	}
}

func TestRollback_ViaHelmClient_GoldenPath(t *testing.T) {
	c, _ := newTestClient(t)
	installViaSDK(t, c, "pi", "default", "0.1.0")

	// SDK 로 한 번 upgrade 해서 revision 2 만듦.
	cfg, _ := c.actionConfig("default")
	upgrade := action.NewUpgrade(cfg)
	upgrade.Namespace = "default"
	if _, err := upgrade.RunWithContext(context.Background(), "pi", minimalChart("pi", "0.2.0"), map[string]interface{}{}); err != nil {
		t.Fatalf("setup Upgrade: %v", err)
	}

	rel, err := c.Rollback(context.Background(), RollbackOptions{
		ReleaseName: "pi",
		Namespace:   "default",
		Revision:    1,
	})
	if err != nil {
		t.Fatalf("Rollback: %v", err)
	}
	if rel == nil {
		t.Errorf("expected rollback release, got nil")
	}
}

// --------------------------------------------------------------------------
// util functions + constructor 보강
// --------------------------------------------------------------------------

func TestStartsWithScheme_KnownSchemes(t *testing.T) {
	cases := []struct {
		input string
		want  bool
	}{
		{"http://repo.example", true},
		{"https://repo.example", true},
		{"oci://repo.example", true},
		{"stable", false},
		{"", false},
		{"ftp://x", false},
		{"http", false}, // "://" 부재.
	}
	for _, c := range cases {
		if got := startsWithScheme(c.input); got != c.want {
			t.Errorf("startsWithScheme(%q) = %v, want %v", c.input, got, c.want)
		}
	}
}

func TestRepoURL_UrlInputBypasses(t *testing.T) {
	settings := cli.New()
	got := repoURL(settings, "https://charts.example.com")
	if got != "https://charts.example.com" {
		t.Errorf("URL input should pass through, got %q", got)
	}
}

func TestRepoURL_AliasNotRegistered_ReturnsInput(t *testing.T) {
	settings := cli.New()
	// non-existent repo file 경로 — best-effort 로 raw 반환.
	settings.RepositoryConfig = "/nonexistent/repositories.yaml"
	got := repoURL(settings, "unknown-alias")
	if got != "unknown-alias" {
		t.Errorf("alias miss should return input, got %q", got)
	}
}

func TestNewClient_PopulatesFields(t *testing.T) {
	c := NewClient(nil).(*helmClient)
	if c.settings == nil {
		t.Errorf("NewClient should set settings")
	}
}

func TestNewClientFromKubeFlags_EmptyPath(t *testing.T) {
	c := NewClientFromKubeFlags("").(*helmClient)
	if c.settings == nil {
		t.Errorf("NewClientFromKubeFlags should set settings")
	}
}

func TestSettings_ReturnsEnvSettings(t *testing.T) {
	c := NewClient(nil).(*helmClient)
	s := c.Settings()
	if s == nil {
		t.Errorf("Settings() should return non-nil EnvSettings")
	}
}

func TestUpgrade_MissingReleaseName_Error(t *testing.T) {
	c, _ := newTestClient(t)
	_, err := c.Upgrade(context.Background(), UpgradeOptions{
		ReleaseName: "",
		Version:     "0.2.0",
		Namespace:   "default",
	})
	if err == nil {
		t.Errorf("Upgrade with empty ReleaseName should error")
	}
}

func TestUpgrade_MissingChartLocator_Error(t *testing.T) {
	c, _ := newTestClient(t)
	_, err := c.Upgrade(context.Background(), UpgradeOptions{
		ReleaseName: "x",
		Version:     "0.2.0",
		Namespace:   "default",
		// Repo / Chart / LocalChartPath 모두 비움
	})
	if err == nil {
		t.Errorf("Upgrade with no chart locator should error")
	}
}

func TestRollback_MissingReleaseName_Error(t *testing.T) {
	c, _ := newTestClient(t)
	_, err := c.Rollback(context.Background(), RollbackOptions{
		ReleaseName: "",
		Namespace:   "default",
		Revision:    1,
	})
	if err == nil {
		t.Errorf("Rollback with empty ReleaseName should error")
	}
}

func TestRollback_NotFound_Error(t *testing.T) {
	c, _ := newTestClient(t)
	_, err := c.Rollback(context.Background(), RollbackOptions{
		ReleaseName: "ghost",
		Namespace:   "default",
		Revision:    1,
	})
	if err == nil {
		t.Errorf("Rollback of non-existent release should error")
	}
}

func TestUninstall_MissingReleaseName_Error(t *testing.T) {
	c, _ := newTestClient(t)
	err := c.Uninstall(context.Background(), UninstallOptions{
		ReleaseName: "",
		Namespace:   "default",
	})
	if err == nil {
		t.Errorf("Uninstall with empty ReleaseName should error")
	}
}

func TestUninstall_Wait_Success(t *testing.T) {
	c, store := newTestClient(t)
	installViaSDK(t, c, "wait-release", "default", "0.1.0")

	err := c.Uninstall(context.Background(), UninstallOptions{
		ReleaseName: "wait-release",
		Namespace:   "default",
		Wait:        true,
		Timeout:     5 * time.Second,
	})
	if err != nil {
		t.Fatalf("Uninstall with Wait: %v", err)
	}
	hist, _ := store.History("wait-release")
	for _, r := range hist {
		if r.Info != nil && r.Info.Status == release.StatusDeployed {
			t.Errorf("still deployed after Uninstall with Wait")
		}
	}
}

func TestInstall_DuplicateRelease_Error(t *testing.T) {
	c, _ := newTestClient(t)
	chartPath := writeChartToTmp(t, minimalChart("dup", "0.1.0"))

	// 첫 install OK.
	if _, err := c.Install(context.Background(), InstallOptions{
		ReleaseName:    "dup-release",
		Version:        "0.1.0",
		Namespace:      "default",
		LocalChartPath: chartPath,
	}); err != nil {
		t.Fatalf("first Install: %v", err)
	}

	// 같은 이름 재 install 시도 — helm SDK 가 cannot re-use a name 에러.
	_, err := c.Install(context.Background(), InstallOptions{
		ReleaseName:    "dup-release",
		Version:        "0.1.0",
		Namespace:      "default",
		LocalChartPath: chartPath,
	})
	if err == nil {
		t.Errorf("duplicate Install should error")
	}
}

func TestStatus_MissingReleaseName_Error(t *testing.T) {
	c, _ := newTestClient(t)
	_, err := c.Status(context.Background(), "default", "")
	if err == nil {
		t.Errorf("Status with empty releaseName should error")
	}
}

func TestHistory_MissingReleaseName_Error(t *testing.T) {
	c, _ := newTestClient(t)
	_, err := c.History(context.Background(), "default", "", 10)
	if err == nil {
		t.Errorf("History with empty releaseName should error")
	}
}

func TestHistory_NotFound_Error(t *testing.T) {
	c, _ := newTestClient(t)
	_, err := c.History(context.Background(), "default", "ghost", 10)
	if err == nil {
		t.Errorf("History of non-existent release should error")
	}
}

func TestUpgrade_WithAtomicAndReuseValues(t *testing.T) {
	c, _ := newTestClient(t)
	chartV1 := writeChartToTmp(t, minimalChart("opt", "0.1.0"))
	chartV2 := writeChartToTmp(t, minimalChart("opt", "0.2.0"))

	if _, err := c.Install(context.Background(), InstallOptions{
		ReleaseName:    "opt",
		Version:        "0.1.0",
		Namespace:      "default",
		LocalChartPath: chartV1,
	}); err != nil {
		t.Fatalf("setup Install: %v", err)
	}

	rel, err := c.Upgrade(context.Background(), UpgradeOptions{
		ReleaseName:    "opt",
		Version:        "0.2.0",
		Namespace:      "default",
		LocalChartPath: chartV2,
		Atomic:         true,
		ReuseValues:    true,
	})
	if err != nil {
		t.Fatalf("Upgrade with Atomic+ReuseValues: %v", err)
	}
	if rel == nil || rel.Revision < 2 {
		t.Errorf("expected revision>=2, got %+v", rel)
	}
}

func TestUpgrade_WithResetValues(t *testing.T) {
	c, _ := newTestClient(t)
	chartV1 := writeChartToTmp(t, minimalChart("reset", "0.1.0"))
	chartV2 := writeChartToTmp(t, minimalChart("reset", "0.2.0"))

	if _, err := c.Install(context.Background(), InstallOptions{
		ReleaseName:    "reset",
		Version:        "0.1.0",
		Namespace:      "default",
		LocalChartPath: chartV1,
		Values:         map[string]interface{}{"old": "value"},
	}); err != nil {
		t.Fatalf("setup Install: %v", err)
	}

	_, err := c.Upgrade(context.Background(), UpgradeOptions{
		ReleaseName:    "reset",
		Version:        "0.2.0",
		Namespace:      "default",
		LocalChartPath: chartV2,
		ResetValues:    true,
	})
	if err != nil {
		t.Fatalf("Upgrade with ResetValues: %v", err)
	}
}

func TestUpgrade_NotFound_Error(t *testing.T) {
	c, _ := newTestClient(t)
	chartPath := writeChartToTmp(t, minimalChart("ghost", "0.1.0"))
	_, err := c.Upgrade(context.Background(), UpgradeOptions{
		ReleaseName:    "ghost",
		Version:        "0.1.0",
		Namespace:      "default",
		LocalChartPath: chartPath,
	})
	if err == nil {
		t.Errorf("Upgrade of non-existent release should error")
	}
}

func TestDriverEnv_AlwaysSecrets(t *testing.T) {
	if got := driverEnv(false); got != "secrets" {
		t.Errorf("driverEnv(false) = %q, want secrets", got)
	}
	if got := driverEnv(true); got != "secrets" {
		t.Errorf("driverEnv(true) = %q, want secrets", got)
	}
}

func TestList_ViaHelmClient_ReturnsAll(t *testing.T) {
	c, _ := newTestClient(t)
	installViaSDK(t, c, "rho-1", "default", "0.1.0")
	installViaSDK(t, c, "rho-2", "default", "0.1.0")

	rels, err := c.List(context.Background(), "default")
	if err != nil {
		t.Fatalf("List: %v", err)
	}
	if len(rels) < 2 {
		t.Errorf("expected >=2 releases, got %d", len(rels))
	}
}
