package config

import (
	"context"
	"testing"

	corev1 "k8s.io/api/core/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/client-go/kubernetes/fake"
)

func TestLoadOnce_ValidPolicy(t *testing.T) {
	cm := &corev1.ConfigMap{
		ObjectMeta: metav1.ObjectMeta{Name: "aipaas-agent-allowlist", Namespace: "aipaas-system"},
		Data: map[string]string{
			"allowed_charts": `- prometheus-community/kube-prometheus-stack:45.0.0-50.0.0
- ingress-nginx/ingress-nginx:4.8.0-4.9.0
`,
			"allowed_namespaces": `- monitoring
- ingress-system
`,
			"allowed_commands": `- LIST_PODS
- INSTALL_ADDON
`,
		},
	}
	cs := fake.NewSimpleClientset(cm)
	loader := NewLoader(cs, "aipaas-system", "aipaas-agent-allowlist")
	if err := loader.LoadOnce(context.Background()); err != nil {
		t.Fatalf("LoadOnce: %v", err)
	}
	policy := loader.Snapshot()

	if len(policy.Charts) != 2 {
		t.Fatalf("charts len = %d, want 2", len(policy.Charts))
	}
	if policy.Charts[0].Repo != "prometheus-community" || policy.Charts[0].Chart != "kube-prometheus-stack" {
		t.Errorf("rule[0] = %+v", policy.Charts[0])
	}
	if policy.Charts[0].MinVersion != "45.0.0" || policy.Charts[0].MaxVersion != "50.0.0" {
		t.Errorf("rule[0] versions = %s..%s", policy.Charts[0].MinVersion, policy.Charts[0].MaxVersion)
	}

	if !policy.IsNamespaceAllowed("monitoring") {
		t.Error("monitoring should be allowed")
	}
	if policy.IsNamespaceAllowed("production") {
		t.Error("production should be denied")
	}

	if !policy.IsCommandAllowed("LIST_PODS") {
		t.Error("LIST_PODS should be allowed")
	}
	if policy.IsCommandAllowed("APPLY_MANIFEST") {
		t.Error("APPLY_MANIFEST should be denied")
	}
}

func TestLoadOnce_MissingConfigMap_DenyAll(t *testing.T) {
	cs := fake.NewSimpleClientset()     // empty.
	loader := NewLoader(cs, "aipaas-system", "aipaas-agent-allowlist")

	err := loader.LoadOnce(context.Background())
	if err == nil {
		t.Fatal("expected error for missing ConfigMap")
	}
	// Snapshot 은 emptyAllowList (deny-all) 이어야 함.
	policy := loader.Snapshot()
	if policy.IsCommandAllowed("LIST_PODS") {
		t.Error("default policy should deny all")
	}
}

func TestFindChartRule(t *testing.T) {
	policy := &AllowList{
		Charts: []ChartRule{
			{Repo: "a", Chart: "b", MinVersion: "1.0.0", MaxVersion: "2.0.0"},
		},
		Namespaces: map[string]struct{}{},
		Commands:   map[string]struct{}{},
	}
	if r := policy.FindChartRule("a", "b"); r == nil {
		t.Error("should find rule for a/b")
	}
	if r := policy.FindChartRule("a", "c"); r != nil {
		t.Error("should not find rule for a/c")
	}
}

func TestParseChartRule(t *testing.T) {
	cases := []struct {
		raw                  string
		repo, chart, min, max string
		shouldErr            bool
	}{
		{"prom/kube-prom:45.0.0-50.0.0", "prom", "kube-prom", "45.0.0", "50.0.0", false},
		{"jetstack/cert-manager:v1.12.0-v1.13.0", "jetstack", "cert-manager", "v1.12.0", "v1.13.0", false},
		{"ns/chart:1.0.0", "ns", "chart", "1.0.0", "1.0.0", false},     // single version.
		{"no-slash:1.0.0", "", "", "", "", true},
		{"prom/chart-no-version", "", "", "", "", true},
	}
	for _, c := range cases {
		rule, err := parseChartRule(c.raw)
		if c.shouldErr {
			if err == nil {
				t.Errorf("%q: expected error", c.raw)
			}
			continue
		}
		if err != nil {
			t.Errorf("%q: unexpected err %v", c.raw, err)
			continue
		}
		if rule.Repo != c.repo || rule.Chart != c.chart {
			t.Errorf("%q: repo/chart = %s/%s", c.raw, rule.Repo, rule.Chart)
		}
		if rule.MinVersion != c.min || rule.MaxVersion != c.max {
			t.Errorf("%q: versions = %s..%s", c.raw, rule.MinVersion, rule.MaxVersion)
		}
	}
}

// IsNamespaceAllowed 의 wildcard "*" — 현재 default 정책 (no-restriction).
// 추후 user-tenant gating 도입 시 narrower 정책으로 override 되더라도 wildcard semantic 자체는
// 안정적으로 유지되어야 회귀 방지.
//
// 회귀 보호: wildcard 는 별도 boolean field 로 표현 — map["*"] lookup 에 의존하지 않음.
// AllowAllNamespaces=true 가 single source of truth.
func TestIsNamespaceAllowed_Wildcard(t *testing.T) {
	policy := &AllowList{
		AllowAllNamespaces: true,
	}
	for _, ns := range []string{"default", "kube-system", "monitoring", "any-random-ns"} {
		if !policy.IsNamespaceAllowed(ns) {
			t.Errorf("wildcard should allow %q", ns)
		}
	}
}

// Parser regression — ConfigMap 의 - "*" 가 정상 wildcard 로 인식되는지 (end-to-end).
func TestParseConfigMap_WildcardNamespace(t *testing.T) {
	cm := &corev1.ConfigMap{
		Data: map[string]string{
			"allowed_namespaces": `- "*"
`,
			"allowed_commands": `- LIST_PODS
`,
		},
	}
	policy, err := parseConfigMap(cm)
	if err != nil {
		t.Fatalf("parseConfigMap: %v", err)
	}
	if !policy.AllowAllNamespaces {
		t.Fatal("AllowAllNamespaces should be true when '*' is in list")
	}
	if len(policy.Namespaces) != 0 {
		t.Errorf("explicit Namespaces map should be empty (wildcard moved to boolean): got %v", policy.Namespaces)
	}
	// IsNamespaceAllowed 가 모든 namespace 허용
	for _, ns := range []string{"default", "kube-system", "monitoring"} {
		if !policy.IsNamespaceAllowed(ns) {
			t.Errorf("end-to-end wildcard failed for %q", ns)
		}
	}
}

// Parser regression — explicit list + wildcard 혼합. wildcard 가 있으면 explicit 무시되고 모두 허용.
func TestParseConfigMap_MixedWildcardAndExplicit(t *testing.T) {
	cm := &corev1.ConfigMap{
		Data: map[string]string{
			"allowed_namespaces": `- "default"
- "*"
- "monitoring"
`,
			"allowed_commands": `- LIST_PODS
`,
		},
	}
	policy, err := parseConfigMap(cm)
	if err != nil {
		t.Fatalf("parseConfigMap: %v", err)
	}
	if !policy.AllowAllNamespaces {
		t.Fatal("AllowAllNamespaces should be true")
	}
	// explicit "default" / "monitoring" 도 map 에 추가됨 — wildcard 가 우선이므로 무해
	if !policy.IsNamespaceAllowed("anything") {
		t.Error("wildcard should allow any namespace")
	}
}

func TestIsNamespaceAllowed_ExplicitList(t *testing.T) {
	policy := &AllowList{
		Namespaces: map[string]struct{}{
			"monitoring":     {},
			"ingress-system": {},
		},
	}
	if !policy.IsNamespaceAllowed("monitoring") {
		t.Error("monitoring should be allowed")
	}
	if policy.IsNamespaceAllowed("default") {
		t.Error("default should be denied — not in explicit list")
	}
}

func TestIsNamespaceAllowed_WildcardOverridesExplicit(t *testing.T) {
	// 일관성 — AllowAllNamespaces=true 이면 명시 ns 가 있든 없든 모두 허용.
	policy := &AllowList{
		AllowAllNamespaces: true,
		Namespaces: map[string]struct{}{
			"monitoring": {},     // redundant but harmless
		},
	}
	if !policy.IsNamespaceAllowed("kube-system") {
		t.Error("wildcard should allow kube-system regardless of explicit entries")
	}
}

func TestIsExecNamespaceAllowed_Wildcard(t *testing.T) {
	policy := &AllowList{
		AllowAllExecNamespaces: true,
	}
	if !policy.IsExecNamespaceAllowed("default") {
		t.Error("exec wildcard should allow default")
	}
}

func TestIsNamespaceAllowed_NilSafe(t *testing.T) {
	var policy *AllowList = nil
	if policy.IsNamespaceAllowed("anything") {
		t.Error("nil policy must deny all")
	}
}

// Chart wildcard — `repo/*:min-max` 는 해당 repo 의 모든 chart 를 version range 내에서 허용.
// exact rule 이 없을 때만 wildcard fallback 사용.
func TestChartWildcard_AnyChartInRepo(t *testing.T) {
	rule, err := parseChartRule("prometheus-community/*:0.0.0-99.99.99")
	if err != nil {
		t.Fatalf("parseChartRule wildcard: %v", err)
	}
	if rule.Repo != "prometheus-community" || rule.Chart != "*" {
		t.Errorf("repo/chart = %s/%s, want prometheus-community/*", rule.Repo, rule.Chart)
	}
	policy := &AllowList{Charts: []ChartRule{rule}}
	for _, chart := range []string{"any-chart", "kube-prometheus-stack", "node-exporter", "random-thing"} {
		if got := policy.FindChartRule("prometheus-community", chart); got == nil {
			t.Errorf("wildcard should match %q", chart)
		} else if got.Chart != "*" {
			t.Errorf("expected wildcard rule for %q, got Chart=%q", chart, got.Chart)
		}
	}
}

// Wildcard 의 version range 가 ChartRule field 에 그대로 보존되는지. 실제 version
// check 는 caller 측 책임 — 본 테스트는 field population 만 검증.
func TestChartWildcard_VersionRangeStillEnforced(t *testing.T) {
	rule, err := parseChartRule("prometheus-community/*:1.0.0-2.5.0")
	if err != nil {
		t.Fatalf("parseChartRule: %v", err)
	}
	if rule.MinVersion != "1.0.0" || rule.MaxVersion != "2.5.0" {
		t.Errorf("versions = %s..%s, want 1.0.0..2.5.0", rule.MinVersion, rule.MaxVersion)
	}
	policy := &AllowList{Charts: []ChartRule{rule}}
	found := policy.FindChartRule("prometheus-community", "any-chart")
	if found == nil {
		t.Fatal("wildcard should return rule")
	}
	if found.MinVersion != "1.0.0" || found.MaxVersion != "2.5.0" {
		t.Errorf("range lost: %s..%s", found.MinVersion, found.MaxVersion)
	}
}

// Exact match 가 wildcard 보다 우선. 운영자가 두 규칙을 동시에 등록한 케이스.
func TestChartWildcard_ExactWinsOverWildcard(t *testing.T) {
	policy := &AllowList{
		Charts: []ChartRule{
			{Repo: "repo", Chart: "foo", MinVersion: "1.0.0", MaxVersion: "1.5.0"},
			{Repo: "repo", Chart: "*", MinVersion: "0.0.0", MaxVersion: "99.99.99"},
		},
	}
	// foo 는 narrower (exact) 규칙으로 매칭.
	got := policy.FindChartRule("repo", "foo")
	if got == nil {
		t.Fatal("should find rule for repo/foo")
	}
	if got.Chart != "foo" || got.MaxVersion != "1.5.0" {
		t.Errorf("expected exact rule (foo, max=1.5.0), got Chart=%q max=%q", got.Chart, got.MaxVersion)
	}
	// 다른 chart 는 wildcard 로 매칭.
	got = policy.FindChartRule("repo", "bar")
	if got == nil {
		t.Fatal("should fallback to wildcard for repo/bar")
	}
	if got.Chart != "*" {
		t.Errorf("expected wildcard for bar, got Chart=%q", got.Chart)
	}
}

// Wildcard 는 repo scope 안에서만 동작. 다른 repo 는 매칭 안 됨.
func TestChartWildcard_DifferentRepo_NoMatch(t *testing.T) {
	policy := &AllowList{
		Charts: []ChartRule{
			{Repo: "repo-a", Chart: "*", MinVersion: "0.0.0", MaxVersion: "99.99.99"},
		},
	}
	if got := policy.FindChartRule("repo-b", "some-chart"); got != nil {
		t.Errorf("wildcard in repo-a must NOT match repo-b: got %+v", got)
	}
	// repo-a 안에선 동작 확인.
	if got := policy.FindChartRule("repo-a", "some-chart"); got == nil {
		t.Error("wildcard in repo-a should match repo-a/some-chart")
	}
}

// `*/*` full wildcard 가 모든 (repo, chart) 조합을 매칭하는지.
// `*/*:0.0.0-99.99.99` 가 dead rule 이었던 버그의 regression test.
func TestChartWildcard_FullWildcard_MatchesAny(t *testing.T) {
	policy := &AllowList{
		Charts: []ChartRule{
			{Repo: "*", Chart: "*", MinVersion: "0.0.0", MaxVersion: "99.99.99"},
		},
	}
	cases := []struct{ repo, chart string }{
		{"prometheus-community", "kube-prometheus-stack"},
		{"jetstack", "cert-manager"},
		{"anycloud-internal", "anything"},
	}
	for _, c := range cases {
		got := policy.FindChartRule(c.repo, c.chart)
		if got == nil {
			t.Errorf("full wildcard must match %s/%s", c.repo, c.chart)
			continue
		}
		if got.Repo != "*" || got.Chart != "*" {
			t.Errorf("matched rule = %+v, want full wildcard", got)
		}
	}
}

// Exact > chart-wildcard > repo-wildcard > full-wildcard. narrower 가 항상 승리.
func TestChartWildcard_PriorityOrder(t *testing.T) {
	policy := &AllowList{
		Charts: []ChartRule{
			{Repo: "*", Chart: "*", MinVersion: "0.0.0", MaxVersion: "99.99.99"},                                  // 4
			{Repo: "*", Chart: "cert-manager", MinVersion: "1.0.0", MaxVersion: "1.99.99"},                        // 3
			{Repo: "prometheus-community", Chart: "*", MinVersion: "0.0.0", MaxVersion: "99.99.99"},               // 2
			{Repo: "prometheus-community", Chart: "kube-prometheus-stack", MinVersion: "65.0.0", MaxVersion: "65.0.0"}, // 1
		},
	}
	// Exact wins.
	if got := policy.FindChartRule("prometheus-community", "kube-prometheus-stack"); got == nil ||
		got.MinVersion != "65.0.0" {
		t.Errorf("exact match priority violated: %+v", got)
	}
	// Chart wildcard wins over repo wildcard (chart-wildcard 가 더 narrower).
	if got := policy.FindChartRule("prometheus-community", "other-chart"); got == nil ||
		got.Repo != "prometheus-community" || got.Chart != "*" {
		t.Errorf("chart-wildcard priority violated: %+v", got)
	}
	// Repo wildcard wins over full wildcard.
	if got := policy.FindChartRule("jetstack", "cert-manager"); got == nil ||
		got.Repo != "*" || got.Chart != "cert-manager" {
		t.Errorf("repo-wildcard priority violated: %+v", got)
	}
	// Fallback to full wildcard for anything else.
	if got := policy.FindChartRule("random-repo", "random-chart"); got == nil ||
		got.Repo != "*" || got.Chart != "*" {
		t.Errorf("full-wildcard fallback violated: %+v", got)
	}
}

// End-to-end — ConfigMap 의 `repo/*:min-max` 가 parseConfigMap 통과 후 AllowList.Charts
// 에 Chart="*" 로 보존되는지.
func TestChartWildcard_ParseAndPersist(t *testing.T) {
	cm := &corev1.ConfigMap{
		Data: map[string]string{
			"allowed_charts": `- "prometheus-community/*:0.0.0-99.99.99"
- "jetstack/cert-manager:v1.12.0-v1.13.0"
`,
			"allowed_commands": `- INSTALL_ADDON
`,
		},
	}
	policy, err := parseConfigMap(cm)
	if err != nil {
		t.Fatalf("parseConfigMap: %v", err)
	}
	if len(policy.Charts) != 2 {
		t.Fatalf("charts len = %d, want 2", len(policy.Charts))
	}
	// 첫 룰은 wildcard.
	if policy.Charts[0].Repo != "prometheus-community" || policy.Charts[0].Chart != "*" {
		t.Errorf("wildcard rule not persisted: %+v", policy.Charts[0])
	}
	if policy.Charts[0].MinVersion != "0.0.0" || policy.Charts[0].MaxVersion != "99.99.99" {
		t.Errorf("wildcard range = %s..%s", policy.Charts[0].MinVersion, policy.Charts[0].MaxVersion)
	}
	// FindChartRule 도 정상 fallback.
	if got := policy.FindChartRule("prometheus-community", "kube-prometheus-stack"); got == nil || got.Chart != "*" {
		t.Errorf("FindChartRule should fallback to wildcard: got %+v", got)
	}
}

// ResourcePolicy — ConfigMap 에 정책이 없으면 legacy 동작 (모든 kind 허용 — RBAC + Namespace
// allowlist 에 의존). backward compatibility 핵심.
func TestParseConfigMap_ResourcePolicy_AllowAllDiscovered_NoDeny(t *testing.T) {
	cm := &corev1.ConfigMap{
		Data: map[string]string{
			"allowed_commands": `- LIST_RESOURCES
`,
			// resource_policy 키 없음 — legacy 동작 기대.
		},
	}
	policy, err := parseConfigMap(cm)
	if err != nil {
		t.Fatalf("parseConfigMap: %v", err)
	}
	if policy.ResourcePolicy != nil {
		t.Fatalf("ResourcePolicy should be nil when ConfigMap omits resource_policy: got %+v", policy.ResourcePolicy)
	}
	// IsResourceKindAllowed 는 nil 정책에서 모든 kind 통과.
	for _, kind := range []string{"pods", "secrets", "configmaps", "storageclasses", "customresourcedefinitions"} {
		if !policy.IsResourceKindAllowed(kind, "default") {
			t.Errorf("legacy (nil policy) should allow %q", kind)
		}
	}
}

// ResourcePolicy — allow_all_discovered + deny 룰. 매칭되는 deny 만 차단.
// Test cases:
//   - secrets 는 global deny (어떤 namespace 든 거부)
//   - configmaps 는 kube-system 에서만 deny
//   - pods 등 다른 kind 는 통과
func TestParseConfigMap_ResourcePolicy_AllowAllDiscovered_WithDeny(t *testing.T) {
	cm := &corev1.ConfigMap{
		Data: map[string]string{
			"allowed_commands": `- LIST_RESOURCES
`,
			"resource_policy": `
mode: allow_all_discovered
deny:
  - kind: secrets
  - kind: configmaps
    namespace: kube-system
`,
		},
	}
	policy, err := parseConfigMap(cm)
	if err != nil {
		t.Fatalf("parseConfigMap: %v", err)
	}
	if policy.ResourcePolicy == nil {
		t.Fatal("ResourcePolicy should be parsed")
	}
	if policy.ResourcePolicy.Mode != "allow_all_discovered" {
		t.Errorf("mode = %q", policy.ResourcePolicy.Mode)
	}
	if len(policy.ResourcePolicy.Deny) != 2 {
		t.Fatalf("deny len = %d, want 2", len(policy.ResourcePolicy.Deny))
	}

	cases := []struct {
		kind, ns string
		want     bool
	}{
		{"secrets", "default", false},          // global deny
		{"secrets", "kube-system", false},
		{"secrets", "", false},
		{"configmaps", "kube-system", false},   // namespace-scoped deny
		{"configmaps", "default", true},        // not denied in this ns
		{"pods", "kube-system", true},          // not in deny list
		{"storageclasses", "", true},           // CRD-ish cluster-scoped — allowed
	}
	for _, c := range cases {
		if got := policy.IsResourceKindAllowed(c.kind, c.ns); got != c.want {
			t.Errorf("IsResourceKindAllowed(%q, %q) = %v, want %v", c.kind, c.ns, got, c.want)
		}
	}
}

// ResourcePolicy — strict 모드. allow 에 명시된 kind 만 통과.
func TestParseConfigMap_ResourcePolicy_Strict(t *testing.T) {
	cm := &corev1.ConfigMap{
		Data: map[string]string{
			"allowed_commands": `- LIST_RESOURCES
`,
			"resource_policy": `
mode: strict
allow:
  - kind: pods
  - kind: deployments
  - kind: storageclasses
`,
		},
	}
	policy, err := parseConfigMap(cm)
	if err != nil {
		t.Fatalf("parseConfigMap: %v", err)
	}
	if policy.ResourcePolicy == nil || policy.ResourcePolicy.Mode != "strict" {
		t.Fatalf("strict policy not parsed: %+v", policy.ResourcePolicy)
	}

	cases := []struct {
		kind, ns string
		want     bool
	}{
		{"pods", "default", true},
		{"deployments", "any-ns", true},
		{"storageclasses", "", true},     // cluster-scoped: caller passes ns=""
		{"secrets", "default", false},    // not in allow list
		{"configmaps", "default", false}, // not in allow list
		{"customresourcedefinitions", "", false},
	}
	for _, c := range cases {
		if got := policy.IsResourceKindAllowed(c.kind, c.ns); got != c.want {
			t.Errorf("strict IsResourceKindAllowed(%q, %q) = %v, want %v", c.kind, c.ns, got, c.want)
		}
	}
}

// ResourcePolicy — strict 모드 + namespace-scoped allow 룰. ns 매칭까지 정확해야 통과.
func TestParseConfigMap_ResourcePolicy_Strict_NamespaceScoped(t *testing.T) {
	cm := &corev1.ConfigMap{
		Data: map[string]string{
			"allowed_commands": `- LIST_RESOURCES
`,
			"resource_policy": `
mode: strict
allow:
  - kind: pods
    namespace: monitoring
`,
		},
	}
	policy, err := parseConfigMap(cm)
	if err != nil {
		t.Fatalf("parseConfigMap: %v", err)
	}
	// pods 는 monitoring 에서만 허용, 다른 namespace 는 거부.
	if !policy.IsResourceKindAllowed("pods", "monitoring") {
		t.Error("pods in monitoring should be allowed")
	}
	if policy.IsResourceKindAllowed("pods", "default") {
		t.Error("pods in default should be denied (not in allow list with matching ns)")
	}
}

// nil-safe — receiver nil 에선 무조건 false (deny-all 의 정의 일관성).
func TestIsResourceKindAllowed_NilSafe(t *testing.T) {
	var policy *AllowList = nil
	if policy.IsResourceKindAllowed("pods", "default") {
		t.Error("nil policy must deny resource kind too")
	}
}

// MetaSnapshot — LoadOnce 후 LastReloadAt 가 non-zero, ConfigMapResourceVersion 가 ConfigMap
// 의 resourceVersion 과 일치해야 함. GET_AGENT_CONFIG 가 본 값을 노출.
func TestLoader_MetaSnapshot_AfterParse(t *testing.T) {
	cm := &corev1.ConfigMap{
		ObjectMeta: metav1.ObjectMeta{
			Name:            "aipaas-agent-allowlist",
			Namespace:       "aipaas-system",
			ResourceVersion: "98765",
		},
		Data: map[string]string{
			"allowed_commands": `- LIST_PODS
`,
		},
	}
	cs := fake.NewSimpleClientset(cm)
	loader := NewLoader(cs, "aipaas-system", "aipaas-agent-allowlist")

	// 초기 (LoadOnce 호출 전) — meta 는 zero-value.
	pre := loader.MetaSnapshot()
	if !pre.LastReloadAt.IsZero() {
		t.Errorf("pre-LoadOnce LastReloadAt should be zero, got %v", pre.LastReloadAt)
	}
	if pre.ConfigMapResourceVersion != "" {
		t.Errorf("pre-LoadOnce CMRV should be empty, got %q", pre.ConfigMapResourceVersion)
	}

	if err := loader.LoadOnce(context.Background()); err != nil {
		t.Fatalf("LoadOnce: %v", err)
	}

	post := loader.MetaSnapshot()
	if post.LastReloadAt.IsZero() {
		t.Error("post-LoadOnce LastReloadAt should be non-zero")
	}
	if post.ConfigMapResourceVersion != "98765" {
		t.Errorf("CMRV = %q, want 98765", post.ConfigMapResourceVersion)
	}
}
