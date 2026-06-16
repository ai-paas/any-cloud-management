// Observability 핸들러.
//
// 본 파일이 처리하는 명령:
//   - INSTALL_OBSERVABILITY_STACK : helm install kube-prometheus-stack (chart 화이트리스트 통과 필수)
//   - QUERY_METRICS               : Prometheus /api/v1/query (instant or range)
//   - LIST_METRIC_TARGETS         : Prometheus /api/v1/targets
//   - LIST_ALERTS                 : Alertmanager /api/v2/alerts
//   - GET_DASHBOARD_URL           : Grafana ingress / service URL 조회 (K8s API)
//
// Prometheus / Alertmanager URL 결정 규칙:
//   - params.prometheus_url / params.alertmanager_url 가 있으면 우선 사용
//   - 그 외엔 default in-cluster DNS:
//       prometheus  : http://kube-prometheus-stack-prometheus.{namespace}.svc:9090
//       alertmanager: http://kube-prometheus-stack-alertmanager.{namespace}.svc:9093
//
// HTTP timeout 은 5s (CommandRequest.timeout_seconds 가 더 짧으면 그것 사용).

package controller

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"time"

	agentv1 "anycloud/agent/internal/gen/agent/v1"
	"anycloud/agent/internal/helm"
	"anycloud/agent/internal/k8s"
	corev1 "k8s.io/api/core/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"google.golang.org/protobuf/types/known/structpb"
)

// installObservabilityStack — helm install kube-prometheus-stack. 기존 installAddon 의 specialization.
// chart 는 항상 "prometheus-community/kube-prometheus-stack" — allowlist 에서 별도 rule 필요.
func (d *Dispatcher) installObservabilityStack(ctx context.Context, cmd *agentv1.CommandRequest) *agentv1.CommandResponse {
	if d.helm == nil {
		return errorResponse(agentv1.Status_AGENT_UNAVAILABLE, "HELM_CLIENT_NIL", "Helm client not initialized")
	}
	if d.allowlist == nil {
		return errorResponse(agentv1.Status_PERMISSION_DENIED, "ALLOWLIST_REQUIRED", "AllowList not loaded")
	}
	namespace := defaultStr(getStringParam(cmd, "namespace"), "monitoring")
	releaseName := defaultStr(getStringParam(cmd, "release_name"), "kube-prometheus-stack")
	chartVersion := defaultStr(getStringParam(cmd, "chart_version"), "65.0.0")

	// repo/chart override 지원 — caller 가 internal mirror redirect 가능.
	// 기본값은 backward-compat 위해 hardcoded "prometheus-community/kube-prometheus-stack".
	repo := defaultStr(getStringParam(cmd, "repo"), "prometheus-community")
	chart := defaultStr(getStringParam(cmd, "chart"), "kube-prometheus-stack")
	// backend 가 HelmRepoEntity.url 명시 전달 → RepositoryFile alias resolve 의존 제거.
	// 비어있으면 helm SDK 가 settings 기반 alias resolve 로 fallback (기존 동작).
	repoURL := getStringParam(cmd, "repo_url")

	// AllowList 검증 — namespace + chart.
	policy := d.allowlist.Snapshot()
	if !policy.IsNamespaceAllowed(namespace) {
		return errorResponse(agentv1.Status_PERMISSION_DENIED, "NAMESPACE_NOT_ALLOWED",
			fmt.Sprintf("namespace %s not in allowlist", namespace))
	}
	rule := policy.FindChartRule(repo, chart)
	if rule == nil {
		return errorResponse(agentv1.Status_PERMISSION_DENIED, "CHART_NOT_ALLOWED",
			fmt.Sprintf("chart %s/%s not in allowlist (observability requires explicit rule)", repo, chart))
	}
	if !versionInRange(chartVersion, rule.MinVersion, rule.MaxVersion) {
		return errorResponse(agentv1.Status_PERMISSION_DENIED, "VERSION_OUT_OF_RANGE",
			fmt.Sprintf("chart_version %s outside [%s, %s]",
				chartVersion, rule.MinVersion, rule.MaxVersion))
	}

	// 사용자 제공 values YAML (optional). JSON 형식 string 으로 받음.
	var values map[string]interface{}
	if valsRaw := getStringParam(cmd, "values_yaml"); valsRaw != "" {
		if err := jsonUnmarshal(valsRaw, &values); err != nil {
			return errorResponse(agentv1.Status_INVALID_PARAMS, "INVALID_VALUES", err.Error())
		}
	}

	rel, err := d.helm.Install(ctx, helm.InstallOptions{
		ReleaseName:     releaseName,
		Namespace:       namespace,
		Repo:            repo,
		Chart:           chart,
		RepoURL:         repoURL,             // 빈 문자열이면 alias resolve fallback.
		Version:         chartVersion,
		Values:          values,
		CreateNamespace: true,
		Timeout:         15 * time.Minute,     // monitoring stack 은 install 시간 김.
	})
	if err != nil {
		return errorResponse(agentv1.Status_FAILED, "HELM_INSTALL_FAILED", err.Error())
	}
	result, _ := structpb.NewStruct(map[string]interface{}{
		"release":       rel.Name,
		"namespace":     rel.Namespace,
		"chart_version": rel.Version,
		"status":        rel.Status,
		"revision":      float64(rel.Revision),
		"agent_instance_id": d.agentInstanceID,
	})
	return okResponse(result)
}

// queryMetrics — Prometheus /api/v1/query (instant) 또는 /api/v1/query_range.
func (d *Dispatcher) queryMetrics(ctx context.Context, cmd *agentv1.CommandRequest) *agentv1.CommandResponse {
	query := getStringParam(cmd, "query")
	if query == "" {
		return errorResponse(agentv1.Status_INVALID_PARAMS, "MISSING_QUERY", "query required")
	}

	// URL 결정 우선순위 — (1) params.prometheus_url, (2) label-based service
	// discovery 결과, (3) namespace 기반 hardcoded fallback.
	promURL := defaultStr(
		getStringParam(cmd, "prometheus_url"),
		discoverPrometheusURL(ctx, d, getStringParam(cmd, "namespace")))

	// range vs instant 결정 — start/end/step 셋 다 있으면 range.
	start := getStringParam(cmd, "start")
	end := getStringParam(cmd, "end")
	step := getStringParam(cmd, "step")
	isRange := start != "" && end != "" && step != ""

	var endpoint string
	q := url.Values{}
	q.Set("query", query)
	if isRange {
		endpoint = promURL + "/api/v1/query_range"
		q.Set("start", start)
		q.Set("end", end)
		q.Set("step", step)
	} else {
		endpoint = promURL + "/api/v1/query"
		if t := getStringParam(cmd, "time"); t != "" {
			q.Set("time", t)
		}
	}
	// Prometheus HTTP API 의 모든 optional param passthrough.
	// timeout: 서버측 시간 제한 (예 "30s"). limit: 결과 row 제한. lookback_delta:
	// staleness lookback. stats: "all" 이면 query stats 반환. 빈 값이면 미포함 (Prometheus default).
	for _, key := range []string{"timeout", "limit", "lookback_delta", "stats"} {
		if v := getStringParam(cmd, key); v != "" {
			q.Set(key, v)
		}
	}

	body, status, err := httpGetJSON(ctx, endpoint+"?"+q.Encode())
	if err != nil {
		return errorResponse(agentv1.Status_FAILED, "PROM_QUERY_FAILED", err.Error())
	}
	if status != http.StatusOK {
		return errorResponse(agentv1.Status_FAILED, "PROM_NON_2XX",
			fmt.Sprintf("Prometheus HTTP %d: %s", status, truncate(string(body), 512)))
	}

	// raw 응답을 result.data 에 그대로 expose — caller (Java starter) 가 PromQLResult 로 파싱.
	result, perr := structFromJSON(map[string]interface{}{
		"prometheus_url": promURL,
		"is_range":       isRange,
		"raw":            string(body),
	})
	if perr != nil {
		return errorResponse(agentv1.Status_FAILED, "ENCODE_FAILED", perr.Error())
	}
	return okResponse(result)
}

// listMetricTargets — Prometheus /api/v1/targets.
func (d *Dispatcher) listMetricTargets(ctx context.Context, cmd *agentv1.CommandRequest) *agentv1.CommandResponse {
	promURL := defaultStr(
		getStringParam(cmd, "prometheus_url"),
		discoverPrometheusURL(ctx, d, getStringParam(cmd, "namespace")))

	endpoint := promURL + "/api/v1/targets"
	if state := getStringParam(cmd, "state"); state != "" {
		endpoint += "?state=" + url.QueryEscape(state)
	}

	body, status, err := httpGetJSON(ctx, endpoint)
	if err != nil {
		return errorResponse(agentv1.Status_FAILED, "PROM_TARGETS_FAILED", err.Error())
	}
	if status != http.StatusOK {
		return errorResponse(agentv1.Status_FAILED, "PROM_NON_2XX",
			fmt.Sprintf("Prometheus HTTP %d", status))
	}
	result, _ := structFromJSON(map[string]interface{}{
		"prometheus_url": promURL,
		"raw":            string(body),
	})
	return okResponse(result)
}

// listAlerts — Alertmanager /api/v2/alerts.
func (d *Dispatcher) listAlerts(ctx context.Context, cmd *agentv1.CommandRequest) *agentv1.CommandResponse {
	amURL := defaultStr(
		getStringParam(cmd, "alertmanager_url"),
		alertmanagerInClusterURL(getStringParam(cmd, "namespace")))

	q := url.Values{}
	for _, k := range []string{"silenced", "inhibited", "active", "unprocessed"} {
		if v := getStringParam(cmd, k); v != "" {
			q.Set(k, v)
		}
	}
	endpoint := amURL + "/api/v2/alerts"
	if len(q) > 0 {
		endpoint += "?" + q.Encode()
	}

	body, status, err := httpGetJSON(ctx, endpoint)
	if err != nil {
		return errorResponse(agentv1.Status_FAILED, "AM_ALERTS_FAILED", err.Error())
	}
	if status != http.StatusOK {
		return errorResponse(agentv1.Status_FAILED, "AM_NON_2XX",
			fmt.Sprintf("Alertmanager HTTP %d", status))
	}
	result, _ := structFromJSON(map[string]interface{}{
		"alertmanager_url": amURL,
		"raw":              string(body),
	})
	return okResponse(result)
}

// getDashboardURL — Grafana service / ingress URL 조회.
// 우선순위: Ingress > LoadBalancer Service > ClusterIP Service (in-cluster URL 만).
func (d *Dispatcher) getDashboardURL(ctx context.Context, cmd *agentv1.CommandRequest) *agentv1.CommandResponse {
	if d.kube == nil {
		return errorResponse(agentv1.Status_AGENT_UNAVAILABLE, "K8S_CLIENT_NIL", "K8s client not initialized")
	}
	namespace := defaultStr(getStringParam(cmd, "namespace"), "monitoring")
	svcName := defaultStr(getStringParam(cmd, "service_name"), "kube-prometheus-stack-grafana")

	// 1) Service GET 으로 type / port / clusterIP 확인.
	raw, err := d.kube.GetResource(ctx, k8s.GetResourceOptions{
		Kind: "service", Namespace: namespace, Name: svcName,
	})
	if err != nil {
		return errorResponse(agentv1.Status_FAILED, "GRAFANA_SERVICE_NOT_FOUND",
			fmt.Sprintf("service %s/%s: %v", namespace, svcName, err))
	}
	var svc corev1.Service
	if err := json.Unmarshal([]byte(raw), &svc); err != nil {
		return errorResponse(agentv1.Status_FAILED, "PARSE_FAILED", err.Error())
	}

	var port int32 = 80
	for _, p := range svc.Spec.Ports {
		if p.Name == "http-web" || p.Name == "http" || p.Name == "service" {
			port = p.Port
			break
		}
		if port == 80 {
			port = p.Port     // fallback: 첫 포트.
		}
	}

	// 2) LoadBalancer external IP 가 있으면 그것 사용.
	if svc.Spec.Type == corev1.ServiceTypeLoadBalancer && len(svc.Status.LoadBalancer.Ingress) > 0 {
		ing := svc.Status.LoadBalancer.Ingress[0]
		host := ing.Hostname
		if host == "" {
			host = ing.IP
		}
		if host != "" {
			return grafanaResult(fmt.Sprintf("http://%s:%d", host, port), host, port, "LoadBalancer")
		}
	}

	// 3) Ingress 검색 (best-effort — 같은 namespace 의 ingress 중 svcName 가 backend 인 것).
	if rl, err := d.kube.ListResources(ctx, k8s.ListResourcesOptions{
		Kind: "ingress", Namespace: namespace, Limit: 50,
	}); err == nil && rl != nil && rl.Items != "" {
		if host := findGrafanaIngressHost(rl.Items, svcName); host != "" {
			return grafanaResult("http://"+host, host, 80, "Ingress")
		}
	}

	// 4) ClusterIP fallback — agent 안에서만 접근 가능, frontend 는 사용 불가. 정보만 제공.
	if svc.Spec.ClusterIP != "" && svc.Spec.ClusterIP != "None" {
		host := fmt.Sprintf("%s.%s.svc", svcName, namespace)
		return errorResponse(agentv1.Status_FAILED, "GRAFANA_NOT_EXPOSED",
			fmt.Sprintf("Grafana only reachable via in-cluster DNS %s:%d — expose via Ingress / LoadBalancer", host, port))
	}
	return errorResponse(agentv1.Status_FAILED, "GRAFANA_NOT_EXPOSED",
		"Grafana service has no external access")
}

func grafanaResult(url, host string, port int32, exposure string) *agentv1.CommandResponse {
	result, _ := structpb.NewStruct(map[string]interface{}{
		"url":      url,
		"host":     host,
		"port":     float64(port),
		"exposure": exposure,
	})
	return okResponse(result)
}

// findGrafanaIngressHost — IngressList JSON 에서 svcName 을 backend 로 갖는 첫 host 반환.
func findGrafanaIngressHost(itemsJSON, svcName string) string {
	var list struct {
		Items []struct {
			Spec struct {
				Rules []struct {
					Host string `json:"host"`
					HTTP struct {
						Paths []struct {
							Backend struct {
								Service struct {
									Name string `json:"name"`
								} `json:"service"`
							} `json:"backend"`
						} `json:"paths"`
					} `json:"http"`
				} `json:"rules"`
			} `json:"spec"`
		} `json:"items"`
	}
	if err := json.Unmarshal([]byte(itemsJSON), &list); err != nil {
		return ""
	}
	for _, ing := range list.Items {
		for _, rule := range ing.Spec.Rules {
			for _, p := range rule.HTTP.Paths {
				if p.Backend.Service.Name == svcName {
					return rule.Host
				}
			}
		}
	}
	return ""
}

// ---- helpers ----

// Prometheus service auto-discovery cache.
// Cluster 안의 service 변화는 잦지 않아 5분 TTL 이면 매 query 마다 LIST 호출 회피 가능.
// 만료 시 다음 호출이 fresh lookup → cache 갱신.
type promCache struct {
	url       string
	expiresAt time.Time
}

var (
	promCacheMu sync.RWMutex
	promCacheV  promCache
)

const promCacheTTL = 5 * time.Minute

// discoverPrometheusURL — label-based 자동 발견 + hardcoded fallback.
//
// Strategy:
//   1. Cache hit 면 즉시 반환 (5분 TTL).
//   2. LIST Service in ns (default "monitoring") with label app.kubernetes.io/name=prometheus.
//      매칭 0개면 prometheus.io/scrape=true 로 재시도 (사용자 custom prometheus 대응).
//   3. ClusterIP 있고 HTTP port 9090 매칭하는 service 의 ClusterIP:port URL 반환.
//   4. 0개 또는 LIST 실패 시 hardcoded fallback (kube-prometheus-stack-prometheus.{ns}.svc:9090).
//
// ns 가 빈 문자열이면 "monitoring" — kube-prometheus-stack 의 default namespace.
//
// Backend 가 cluster row 의 monit_server_url 컬럼 없이 metric 조회 가능.
func discoverPrometheusURL(ctx context.Context, d *Dispatcher, ns string) string {
	ns = defaultStr(ns, "monitoring")

	// 1) cache lookup
	promCacheMu.RLock()
	if promCacheV.url != "" && time.Now().Before(promCacheV.expiresAt) {
		cached := promCacheV.url
		promCacheMu.RUnlock()
		return cached
	}
	promCacheMu.RUnlock()

	// 2) label-based service lookup. ListResources 가 JSON-encoded items 반환.
	candidates := []string{
		"app.kubernetes.io/name=prometheus",
		"prometheus.io/scrape=true",
	}
	for _, sel := range candidates {
		url := tryFindPromService(ctx, d, ns, sel)
		if url == "" {
			continue
		}
		promCacheMu.Lock()
		promCacheV = promCache{url: url, expiresAt: time.Now().Add(promCacheTTL)}
		promCacheMu.Unlock()
		return url
	}

	// 3) fallback
	return prometheusInClusterURL(ns)
}

func tryFindPromService(ctx context.Context, d *Dispatcher, ns, selector string) string {
	if d == nil || d.kube == nil {
		return ""
	}
	res, err := d.kube.ListResources(ctx, k8s.ListResourcesOptions{
		Kind:          "service",
		Namespace:     ns,
		Limit:         20,
		LabelSelector: selector,
	})
	if err != nil || res == nil || res.Items == "" {
		return ""
	}
	// 응답 JSON parse — corev1.Service list 모양. minimal parse 만.
	var list struct {
		Items []corev1.Service `json:"items"`
	}
	if err := json.Unmarshal([]byte(res.Items), &list); err != nil {
		return ""
	}
	for _, svc := range list.Items {
		if svc.Spec.ClusterIP == "" || svc.Spec.ClusterIP == "None" {
			continue
		}
		// Port 9090 또는 name="web"/"http-web"/"http" 우선.
		for _, p := range svc.Spec.Ports {
			if p.Port == 9090 || p.Name == "web" || p.Name == "http-web" || p.Name == "http" {
				return fmt.Sprintf("http://%s.%s.svc:%d", svc.Name, svc.Namespace, p.Port)
			}
		}
		// fallback — 첫 port
		if len(svc.Spec.Ports) > 0 {
			return fmt.Sprintf("http://%s.%s.svc:%d", svc.Name, svc.Namespace, svc.Spec.Ports[0].Port)
		}
	}
	return ""
}

func prometheusInClusterURL(ns string) string {
	ns = defaultStr(ns, "monitoring")
	// kube-prometheus-stack 의 기본 service 이름.
	return fmt.Sprintf("http://kube-prometheus-stack-prometheus.%s.svc:9090", ns)
}

func alertmanagerInClusterURL(ns string) string {
	ns = defaultStr(ns, "monitoring")
	return fmt.Sprintf("http://kube-prometheus-stack-alertmanager.%s.svc:9093", ns)
}

func defaultStr(value, fallback string) string {
	if strings.TrimSpace(value) == "" {
		return fallback
	}
	return value
}

func truncate(s string, max int) string {
	if len(s) <= max {
		return s
	}
	return s[:max] + "...(truncated)"
}

func httpGetJSON(ctx context.Context, urlStr string) ([]byte, int, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, urlStr, nil)
	if err != nil {
		return nil, 0, fmt.Errorf("build request: %w", err)
	}
	req.Header.Set("Accept", "application/json")

	// 5s default — agent 가 backend 응답 timeout 보다 짧게 잡아 cascade 회피.
	client := &http.Client{Timeout: 5 * time.Second}
	resp, err := client.Do(req)
	if err != nil {
		return nil, 0, fmt.Errorf("http call: %w", err)
	}
	defer func() { _ = resp.Body.Close() }()

	// 1MB 상한 — Prometheus 응답이 매우 큰 경우 메모리 보호.
	body, err := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	if err != nil {
		return nil, resp.StatusCode, fmt.Errorf("read body: %w", err)
	}
	return body, resp.StatusCode, nil
}

// POST/DELETE helpers for Alertmanager silence API.

func httpRequestJSON(ctx context.Context, method, urlStr string, body []byte) ([]byte, int, error) {
	var reqBody io.Reader
	if body != nil {
		reqBody = bytes.NewReader(body)
	}
	req, err := http.NewRequestWithContext(ctx, method, urlStr, reqBody)
	if err != nil {
		return nil, 0, fmt.Errorf("build request: %w", err)
	}
	req.Header.Set("Accept", "application/json")
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}

	client := &http.Client{Timeout: 5 * time.Second}
	resp, err := client.Do(req)
	if err != nil {
		return nil, 0, fmt.Errorf("http call: %w", err)
	}
	defer func() { _ = resp.Body.Close() }()
	rb, err := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	if err != nil {
		return nil, resp.StatusCode, fmt.Errorf("read body: %w", err)
	}
	return rb, resp.StatusCode, nil
}

// listAlertSilences — Alertmanager /api/v2/silences (GET).
func (d *Dispatcher) listAlertSilences(ctx context.Context, cmd *agentv1.CommandRequest) *agentv1.CommandResponse {
	amURL := defaultStr(
		getStringParam(cmd, "alertmanager_url"),
		alertmanagerInClusterURL(getStringParam(cmd, "namespace")))
	body, status, err := httpGetJSON(ctx, amURL+"/api/v2/silences")
	if err != nil {
		return errorResponse(agentv1.Status_FAILED, "AM_SILENCES_FAILED", err.Error())
	}
	if status != http.StatusOK {
		return errorResponse(agentv1.Status_FAILED, "AM_NON_2XX",
			fmt.Sprintf("Alertmanager HTTP %d", status))
	}
	result, _ := structFromJSON(map[string]interface{}{
		"alertmanager_url": amURL,
		"raw":              string(body),
	})
	return okResponse(result)
}

// createAlertSilence — Alertmanager /api/v2/silences (POST).
// params 의 matchers/startsAt/endsAt/createdBy/comment 를 그대로 JSON body 로 전달.
func (d *Dispatcher) createAlertSilence(ctx context.Context, cmd *agentv1.CommandRequest) *agentv1.CommandResponse {
	amURL := defaultStr(
		getStringParam(cmd, "alertmanager_url"),
		alertmanagerInClusterURL(getStringParam(cmd, "namespace")))
	matchersJSON := getStringParam(cmd, "matchers")
	startsAt := getStringParam(cmd, "startsAt")
	endsAt := getStringParam(cmd, "endsAt")
	createdBy := getStringParam(cmd, "createdBy")
	comment := getStringParam(cmd, "comment")
	if matchersJSON == "" || startsAt == "" || endsAt == "" || createdBy == "" || comment == "" {
		return errorResponse(agentv1.Status_INVALID_PARAMS, "MISSING_PARAM",
			"matchers, startsAt, endsAt, createdBy, comment all required")
	}

	// Alertmanager schema 그대로 — matchers 가 이미 JSON array of {name, value, isRegex, isEqual}.
	var matchers []interface{}
	if err := json.Unmarshal([]byte(matchersJSON), &matchers); err != nil {
		return errorResponse(agentv1.Status_INVALID_PARAMS, "INVALID_MATCHERS", err.Error())
	}
	payload := map[string]interface{}{
		"matchers":  matchers,
		"startsAt":  startsAt,
		"endsAt":    endsAt,
		"createdBy": createdBy,
		"comment":   comment,
	}
	encoded, err := json.Marshal(payload)
	if err != nil {
		return errorResponse(agentv1.Status_FAILED, "MARSHAL_FAILED", err.Error())
	}

	body, status, err := httpRequestJSON(ctx, http.MethodPost, amURL+"/api/v2/silences", encoded)
	if err != nil {
		return errorResponse(agentv1.Status_FAILED, "AM_SILENCE_FAILED", err.Error())
	}
	if status != http.StatusOK && status != http.StatusCreated {
		return errorResponse(agentv1.Status_FAILED, "AM_NON_2XX",
			fmt.Sprintf("Alertmanager HTTP %d: %s", status, string(body)))
	}

	// 응답 — { silenceID: "..." } (Alertmanager v2). raw 그대로 전달 + 추출.
	var resp map[string]interface{}
	silenceID := ""
	if e := json.Unmarshal(body, &resp); e == nil {
		if sid, ok := resp["silenceID"].(string); ok {
			silenceID = sid
		}
	}
	result, _ := structFromJSON(map[string]interface{}{
		"alertmanager_url": amURL,
		"silence_id":       silenceID,
		"raw":              string(body),
	})
	return okResponse(result)
}

// deleteAlertSilence — Alertmanager /api/v2/silence/{id} (DELETE).
func (d *Dispatcher) deleteAlertSilence(ctx context.Context, cmd *agentv1.CommandRequest) *agentv1.CommandResponse {
	amURL := defaultStr(
		getStringParam(cmd, "alertmanager_url"),
		alertmanagerInClusterURL(getStringParam(cmd, "namespace")))
	silenceID := getStringParam(cmd, "silence_id")
	if silenceID == "" {
		return errorResponse(agentv1.Status_INVALID_PARAMS, "MISSING_PARAM", "silence_id required")
	}

	body, status, err := httpRequestJSON(ctx, http.MethodDelete,
		amURL+"/api/v2/silence/"+url.PathEscape(silenceID), nil)
	if err != nil {
		return errorResponse(agentv1.Status_FAILED, "AM_DELETE_FAILED", err.Error())
	}
	if status != http.StatusOK && status != http.StatusNoContent {
		return errorResponse(agentv1.Status_FAILED, "AM_NON_2XX",
			fmt.Sprintf("Alertmanager HTTP %d: %s", status, string(body)))
	}
	result, _ := structFromJSON(map[string]interface{}{
		"alertmanager_url": amURL,
		"silence_id":       silenceID,
		"deleted":          true,
	})
	return okResponse(result)
}

func structFromJSON(m map[string]interface{}) (*structpb.Struct, error) {
	return structpb.NewStruct(m)
}

// ---- 미사용 import 회피 (corev1, metav1) 가드 — Service Get 시 사용 ----
var _ = metav1.GetOptions{}
