// Package config — agent 의 동적 설정 로딩. ConfigMap watch 로 변경 즉시 반영.
//
// AllowList: chart name / version range / namespace / command type 화이트리스트.
// Helm install / uninstall 명령은 본 list 통과해야만 dispatch 됨.
//
// ConfigMap 형식 (aipaas-agent-allowlist):
//
//   apiVersion: v1
//   kind: ConfigMap
//   metadata:
//     name: aipaas-agent-allowlist
//     namespace: aipaas-system
//   data:
//     allowed_charts: |
//       - prometheus-community/kube-prometheus-stack:45.0.0-50.0.0
//       - ingress-nginx/ingress-nginx:4.8.0-4.9.0
//       - jetstack/cert-manager:v1.12.0-v1.13.0
//     allowed_namespaces: |
//       - monitoring
//       - ingress-system
//       - cert-manager
//     allowed_commands: |
//       - LIST_PODS
//       - GET_LOG
//       - GET_CLUSTER_INFO
//       - INSTALL_ADDON
//       - UNINSTALL_ADDON
//       - LIST_HELM_RELEASES
//       - EXEC_POD                                  # PodExec WebSocket terminal.
//     allowed_exec_namespaces: |                    # PodExec target namespace 화이트리스트.
//       - default
//       - apps
//
// Default policy = deny-all. allowed_commands 에 없는 명령은 dispatcher 가 PERMISSION_DENIED.
// PodExec 은 allowed_exec_namespaces 도 추가로 통과해야 함 (kube-system 등 격리).
package config

import (
	"context"
	"fmt"
	"log/slog"
	"strings"
	"sync"
	"time"

	corev1 "k8s.io/api/core/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/fields"
	"k8s.io/apimachinery/pkg/watch"
	"k8s.io/client-go/kubernetes"
	"sigs.k8s.io/yaml"
)

// ChartRule — "prometheus-community/kube-prometheus-stack:45.0.0-50.0.0" 파싱 결과.
type ChartRule struct {
	Repo         string     // "prometheus-community"
	Chart        string     // "kube-prometheus-stack"
	MinVersion   string     // "45.0.0"  (semver, inclusive)
	MaxVersion   string     // "50.0.0"  (semver, inclusive)
}

// AllowList — 현재 활성 정책. snapshot — 변경 시 atomic 교체.
//
// Wildcard 처리: ConfigMap 의 allowed_namespaces / allowed_exec_namespaces 안에 "*" 가 포함되면
// 별도 boolean field (AllowAllNamespaces / AllowAllExecNamespaces) 로 분리. map key 의 string
// 매칭에 의존하지 않으므로 sigs.k8s.io/yaml 의 unmarshal 결과가 control char / whitespace 를 포함
// 하는 케이스에서도 안전. 이전 버전 (map["*"] 직접 lookup) 에서 wildcard 가 정상 로드돼도 매칭
// 실패하던 회귀를 회피.
type AllowList struct {
	Charts                 []ChartRule
	Namespaces             map[string]struct{}
	Commands               map[string]struct{}
	ExecNamespaces         map[string]struct{}     // PodExec 전용 namespace 화이트리스트.
	AllowAllNamespaces     bool                    // "*" 가 allowed_namespaces 안에 있는 경우 true.
	AllowAllExecNamespaces bool                    // PodExec wildcard.

	// ResourcePolicy — LIST/GET/DELETE resource 명령의 kind-level 화이트/블랙리스트.
	// nil = legacy 동작 (allow-all-discovered: RESTMapper 가 해석한 모든 자원 통과,
	// 안전성은 RBAC + Namespaces 화이트리스트에 의존).
	ResourcePolicy *ResourcePolicy
}

// ResourcePolicy — kind-level allow/deny 정책. Mode = "strict" 면 Allow 화이트리스트만 통과.
// Mode = "allow_all_discovered" (기본) 면 Deny 룰만 차단.
//
// ConfigMap data key "resource_policy" (YAML) 로 주입:
//
//	resource_policy: |
//	  mode: allow_all_discovered
//	  deny:
//	    - kind: secrets
//	    - kind: configmaps
//	      namespace: kube-system
//	  allow:
//	    - kind: pods
type ResourcePolicy struct {
	Mode  string         `json:"mode,omitempty"`     // "allow_all_discovered" | "strict"
	Deny  []ResourceRule `json:"deny,omitempty"`
	Allow []ResourceRule `json:"allow,omitempty"`
}

// ResourceRule — 단일 kind/namespace 룰. Kind 는 lowercase plural (예: "pods", "storageclasses").
// Namespace="" 는 모든 namespace 매칭 (cluster-scoped 자원에도 자연스럽게 적용).
type ResourceRule struct {
	Kind      string     `json:"kind,omitempty"`
	Namespace string     `json:"namespace,omitempty"`
}

// ResourcePolicy mode 상수.
const (
	resourcePolicyModeAllowAll = "allow_all_discovered"
	resourcePolicyModeStrict   = "strict"
)

// LoaderMeta — Snapshot 의 metadata (last reload + ConfigMap resourceVersion).
//
// Reload 가 한 번도 안 일어난 (LoadOnce 실패 + watch 미수신) 상태에선 LastReloadAt = zero
// time, ConfigMapResourceVersion = "". GET_AGENT_CONFIG 가 이 값을 그대로 직렬화해서
// UI 의 "마지막 reload 시각" 표시에 사용.
type LoaderMeta struct {
	LastReloadAt             time.Time
	ConfigMapResourceVersion string
}

// Loader — ConfigMap 에서 정책 로드 + watch. Lock-free snapshot read (sync.Map 대신 RWMutex).
type Loader struct {
	cs            kubernetes.Interface
	namespace     string
	configMapName string

	mu      sync.RWMutex
	current *AllowList
	meta    LoaderMeta     // current 와 동일 lock 으로 보호.
}

func NewLoader(cs kubernetes.Interface, namespace, configMapName string) *Loader {
	return &Loader{
		cs:            cs,
		namespace:     namespace,
		configMapName: configMapName,
		// Default = deny-all (Commands map empty).
		current: emptyAllowList(),
	}
}

// LoadOnce — 초기 로드. ConfigMap 없으면 deny-all 유지.
func (l *Loader) LoadOnce(ctx context.Context) error {
	cm, err := l.cs.CoreV1().ConfigMaps(l.namespace).Get(ctx, l.configMapName, metav1.GetOptions{})
	if err != nil {
		slog.Warn("allowlist: ConfigMap not found — default deny-all",
			slog.String("namespace", l.namespace),
			slog.String("name", l.configMapName),
			slog.String("error", err.Error()))
		return err
	}
	policy, perr := parseConfigMap(cm)
	if perr != nil {
		return fmt.Errorf("parse allowlist: %w", perr)
	}
	l.swap(policy, LoaderMeta{
		LastReloadAt:             time.Now().UTC(),
		ConfigMapResourceVersion: cm.GetResourceVersion(),
	})
	return nil
}

// Watch — ConfigMap 변경 watch. Stream 이 close 되면 caller 가 재호출.
func (l *Loader) Watch(ctx context.Context) error {
	w, err := l.cs.CoreV1().ConfigMaps(l.namespace).Watch(ctx, metav1.ListOptions{
		FieldSelector: fields.OneTermEqualSelector("metadata.name", l.configMapName).String(),
	})
	if err != nil {
		return fmt.Errorf("watch allowlist: %w", err)
	}
	defer w.Stop()
	for {
		select {
		case <-ctx.Done():
			return ctx.Err()
		case event, ok := <-w.ResultChan():
			if !ok {
				return nil     // channel closed — caller 가 재시작.
			}
			switch event.Type {
			case watch.Added, watch.Modified:
				if cm, isCM := event.Object.(*corev1.ConfigMap); isCM {
					policy, perr := parseConfigMap(cm)
					if perr != nil {
						slog.Warn("allowlist: parse error — keeping previous policy",
							slog.String("error", perr.Error()))
						continue
					}
					l.swap(policy, LoaderMeta{
						LastReloadAt:             time.Now().UTC(),
						ConfigMapResourceVersion: cm.GetResourceVersion(),
					})
					slog.Info("allowlist: policy updated",
						slog.Int("charts", len(policy.Charts)),
						slog.Int("namespaces", len(policy.Namespaces)),
						slog.Int("commands", len(policy.Commands)))
				}
			case watch.Deleted:
				slog.Warn("allowlist: ConfigMap deleted — fallback to deny-all")
				// Deleted 도 reload event — meta 갱신 (UI 가 "정책 사라짐" 인지).
				l.swap(emptyAllowList(), LoaderMeta{
					LastReloadAt: time.Now().UTC(),
				})
			}
		}
	}
}

// Snapshot — 현재 정책의 lock-free read 용 복사본. Dispatcher 가 매 명령 시점에 호출.
func (l *Loader) Snapshot() *AllowList {
	l.mu.RLock()
	defer l.mu.RUnlock()
	return l.current
}

// MetaSnapshot — Snapshot 의 metadata. Reload 가 한 번도 안 일어났으면 zero-value 반환.
func (l *Loader) MetaSnapshot() LoaderMeta {
	l.mu.RLock()
	defer l.mu.RUnlock()
	return l.meta
}

func (l *Loader) swap(p *AllowList, meta LoaderMeta) {
	l.mu.Lock()
	l.current = p
	l.meta = meta
	l.mu.Unlock()
}

// IsCommandAllowed — command type name (예: "INSTALL_ADDON") 가 허용 목록에 있는지.
func (a *AllowList) IsCommandAllowed(name string) bool {
	if a == nil {
		return false
	}
	_, ok := a.Commands[name]
	return ok
}

// IsNamespaceAllowed — Helm install / read 명령의 target namespace 검증.
//
// Wildcard 지원: ConfigMap 의 allowed_namespaces 에 "*" 가 있으면 {@link AllowAllNamespaces}
// boolean field 가 true 로 설정되어 모든 namespace 통과. 현재 default 정책 (no-restriction).
// 추후 user-tenant gating 도입 시 운영자가 명시 ns 목록으로 override.
func (a *AllowList) IsNamespaceAllowed(name string) bool {
	if a == nil {
		return false
	}
	if a.AllowAllNamespaces {
		return true
	}
	_, ok := a.Namespaces[name]
	return ok
}

// IsExecNamespaceAllowed — PodExec 대상 namespace 가 화이트리스트에 있는지.
// kube-system 등 critical namespace 격리 목적. wildcard "*" 동일 지원하지만 일반적으로
// PodExec 은 명시 ns 만 허용 권장 (운영 강제 의미를 살리기 위해).
func (a *AllowList) IsExecNamespaceAllowed(name string) bool {
	if a == nil {
		return false
	}
	if a.AllowAllExecNamespaces {
		return true
	}
	_, ok := a.ExecNamespaces[name]
	return ok
}

// IsResourceKindAllowed — LIST/GET/DELETE 명령의 kind-level 정책 검사.
//
// Plural 입력: k8s.Client.ResolveResource(...).Plural 가 normalize 한 lowercase plural
// (예: "pods", "storageclasses", "customresourcedefinitions"). short name ("pvc", "sc") 으로
// 전달되면 정책에 plural 로 적힌 룰과 일치하지 않을 수 있으니 caller 는 plural 만 넘긴다.
//
// Namespace 의미: cluster-scoped 자원이면 "" 가 전달됨. Deny/Allow 룰의 Namespace="" 는
// "어떤 namespace 든" 의미 — cluster-scoped 자원도 자연스럽게 적용.
//
// 정책 우선순위:
//  1. ResourcePolicy == nil → return true (legacy / backwards-compatible. RBAC 의존).
//  2. Mode == "strict" → Allow 에 매칭되는 룰이 있으면 통과, 없으면 deny.
//  3. Mode == "allow_all_discovered" (또는 default) → Deny 매칭 룰 있으면 deny, 그 외 통과.
func (a *AllowList) IsResourceKindAllowed(resourcePlural, namespace string) bool {
	if a == nil {
		return false
	}
	if a.ResourcePolicy == nil {
		// Legacy: kind-level 정책 미지정 = 모든 kind 통과. RBAC + Namespaces 가 안전성 책임.
		return true
	}
	pl := strings.ToLower(resourcePlural)
	mode := a.ResourcePolicy.Mode
	if mode == "" {
		mode = resourcePolicyModeAllowAll
	}
	switch mode {
	case resourcePolicyModeStrict:
		for _, r := range a.ResourcePolicy.Allow {
			if matchResourceRule(r, pl, namespace) {
				return true
			}
		}
		return false
	default:
		// allow_all_discovered: deny 매칭 시만 차단.
		for _, r := range a.ResourcePolicy.Deny {
			if matchResourceRule(r, pl, namespace) {
				return false
			}
		}
		return true
	}
}

// matchResourceRule — Kind/Namespace 매칭. ResourceRule.Namespace="" 면 namespace 무관 매칭.
func matchResourceRule(rule ResourceRule, plural, namespace string) bool {
	if strings.ToLower(rule.Kind) != plural {
		return false
	}
	if rule.Namespace == "" {
		return true
	}
	return rule.Namespace == namespace
}

// FindChartRule — "repo/chart" 가 매칭되는 첫 rule 반환. 없으면 nil.
//
// 매칭 우선순위 (narrower 가 항상 승리):
//  1. Exact match           — Repo=<r>      Chart=<c>      (정확 매칭)
//  2. Chart wildcard        — Repo=<r>      Chart="*"      (repo 내 모든 chart)
//  3. Repo wildcard         — Repo="*"      Chart=<c>      (모든 repo 의 특정 chart)
//  4. Full wildcard         — Repo="*"      Chart="*"      (모든 chart 허용)
//
// 운영자가 동시에 `repo/foo:1.0.0-1.5.0` 과 `*/*:0.0.0-99.99.99` 를 등록하면,
// foo 는 narrower (exact) 규칙으로, 그 외 chart 는 full wildcard 로 매칭. 버전 range 는
// caller 측에서 ChartRule.MinVersion/MaxVersion 으로 추가 검증 — 범위 밖이면 caller
// 가 CHART_NOT_ALLOWED 를 반환.
//
// `*/*:0.0.0-99.99.99` full-wildcard default 가 dead rule 이었던 버그 수정.
// Repo="*" 케이스를 처리하지 않아 모든 INSTALL_OBSERVABILITY_STACK 호출이
// CHART_NOT_ALLOWED 로 거부되던 문제 해결.
func (a *AllowList) FindChartRule(repo, chart string) *ChartRule {
	if a == nil {
		return nil
	}
	// 1. Exact match — narrowest. repo + chart 모두 정확 일치.
	for i := range a.Charts {
		if a.Charts[i].Repo == repo && a.Charts[i].Chart == chart {
			return &a.Charts[i]
		}
	}
	// 2. Chart wildcard — repo 정확 일치 + Chart="*".
	for i := range a.Charts {
		if a.Charts[i].Repo == repo && a.Charts[i].Chart == "*" {
			return &a.Charts[i]
		}
	}
	// 3. Repo wildcard — Repo="*" + chart 정확 일치.
	for i := range a.Charts {
		if a.Charts[i].Repo == "*" && a.Charts[i].Chart == chart {
			return &a.Charts[i]
		}
	}
	// 4. Full wildcard — */* allows anything (default).
	for i := range a.Charts {
		if a.Charts[i].Repo == "*" && a.Charts[i].Chart == "*" {
			return &a.Charts[i]
		}
	}
	return nil
}

// ---- internal parsing helpers ----

func parseConfigMap(cm *corev1.ConfigMap) (*AllowList, error) {
	policy := emptyAllowList()

	charts, err := parseYAMLList(cm.Data["allowed_charts"])
	if err != nil {
		return nil, fmt.Errorf("allowed_charts: %w", err)
	}
	for _, raw := range charts {
		rule, parseErr := parseChartRule(raw)
		if parseErr != nil {
			return nil, fmt.Errorf("chart rule %q: %w", raw, parseErr)
		}
		policy.Charts = append(policy.Charts, rule)
	}

	nss, err := parseYAMLList(cm.Data["allowed_namespaces"])
	if err != nil {
		return nil, fmt.Errorf("allowed_namespaces: %w", err)
	}
	for _, n := range nss {
		if strings.TrimSpace(n) == "*" {
			policy.AllowAllNamespaces = true
			continue
		}
		policy.Namespaces[n] = struct{}{}
	}

	cmds, err := parseYAMLList(cm.Data["allowed_commands"])
	if err != nil {
		return nil, fmt.Errorf("allowed_commands: %w", err)
	}
	for _, c := range cmds {
		policy.Commands[c] = struct{}{}
	}

	execNs, err := parseYAMLList(cm.Data["allowed_exec_namespaces"])
	if err != nil {
		return nil, fmt.Errorf("allowed_exec_namespaces: %w", err)
	}
	for _, n := range execNs {
		if strings.TrimSpace(n) == "*" {
			policy.AllowAllExecNamespaces = true
			continue
		}
		policy.ExecNamespaces[n] = struct{}{}
	}

	// ResourcePolicy — optional. 없으면 nil 유지 (legacy allow-all 동작).
	if raw := cm.Data["resource_policy"]; strings.TrimSpace(raw) != "" {
		var rp ResourcePolicy
		if err := yaml.Unmarshal([]byte(raw), &rp); err != nil {
			return nil, fmt.Errorf("resource_policy: %w", err)
		}
		// Kind 는 lowercase 로 정규화 (정책 일관성 — caller 도 lowercase plural 로 검사).
		for i := range rp.Deny {
			rp.Deny[i].Kind = strings.ToLower(strings.TrimSpace(rp.Deny[i].Kind))
		}
		for i := range rp.Allow {
			rp.Allow[i].Kind = strings.ToLower(strings.TrimSpace(rp.Allow[i].Kind))
		}
		policy.ResourcePolicy = &rp
	}

	return policy, nil
}

// parseYAMLList — ConfigMap data value 가 YAML list 문자열. sigs.k8s.io/yaml 사용.
func parseYAMLList(value string) ([]string, error) {
	if value == "" {
		return nil, nil
	}
	var result []string
	if err := yaml.Unmarshal([]byte(value), &result); err != nil {
		return nil, err
	}
	return result, nil
}

// parseChartRule — "repo/chart:min-max" or "repo/chart:single-version".
// version 부분에 "-" 있으면 min-max range, 없으면 단일 version (min=max).
func parseChartRule(raw string) (ChartRule, error) {
	// repo/chart:version
	slash := strings.Index(raw, "/")
	if slash <= 0 {
		return ChartRule{}, fmt.Errorf("missing repo prefix: %q", raw)
	}
	colon := strings.LastIndex(raw, ":")
	if colon <= slash {
		return ChartRule{}, fmt.Errorf("missing version: %q", raw)
	}
	repo := raw[:slash]
	chart := raw[slash+1 : colon]
	versionPart := raw[colon+1:]

	rule := ChartRule{Repo: repo, Chart: chart}
	if dash := strings.Index(versionPart, "-"); dash > 0 {
		rule.MinVersion = strings.TrimSpace(versionPart[:dash])
		rule.MaxVersion = strings.TrimSpace(versionPart[dash+1:])
	} else {
		rule.MinVersion = versionPart
		rule.MaxVersion = versionPart
	}
	if rule.Repo == "" || rule.Chart == "" || rule.MinVersion == "" {
		return ChartRule{}, fmt.Errorf("empty field in %q", raw)
	}
	return rule, nil
}

func emptyAllowList() *AllowList {
	return &AllowList{
		Charts:         nil,
		Namespaces:     map[string]struct{}{},
		Commands:       map[string]struct{}{},
		ExecNamespaces: map[string]struct{}{},
	}
}
