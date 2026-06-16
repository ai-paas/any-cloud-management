// Helm release 핸들러.
//
// 본 파일이 처리하는 명령:
//   - INSTALL_ADDON                : chart 화이트리스트 + version range 통과 후 helm install
//   - UPGRADE_ADDON                : 기존 release 의 chart/version/values 업그레이드
//   - UNINSTALL_ADDON              : release 제거 (keepHistory / wait 옵션)
//   - LIST_HELM_RELEASES           : namespace 단위 release 목록
//   - GET_HELM_RELEASE_STATUS      : 단일 release status (action.NewStatus)
//   - GET_HELM_RELEASE_HISTORY     : revision 이력 (action.NewHistory)
//   - ROLLBACK_HELM_RELEASE        : 지정 revision 으로 복원
//   - LIST_HELM_RELEASE_RESOURCES  : release 가 만든 K8s 자원 enumerate (app.kubernetes.io/instance label)
//
// Helm install/upgrade/uninstall concurrency lock:
// 동일 (namespace, release) 의 lifecycle ops 를 per-key mutex 로 직렬화. helm SDK 자체는
// serialize 안 함 — 두 caller 가 같은 key 로 동시 호출 시 "release already exists" 또는
// partial state 발생. install/upgrade/uninstall 모두 같은 mutex 공유.

package controller

import (
	"context"
	"encoding/base64"
	"fmt"
	"log/slog"
	"os"
	"strconv"
	"strings"
	"sync"
	"time"

	agentv1 "anycloud/agent/internal/gen/agent/v1"
	"anycloud/agent/internal/helm"
	"anycloud/agent/internal/k8s"
	"google.golang.org/protobuf/types/known/structpb"
)

// Helm install/upgrade concurrency lock.
//
// 동일 release name 에 대한 동시 install/upgrade race 차단. Helm SDK 자체는 serialize 안 함 —
// 두 caller 가 같은 (cluster, namespace, releaseName) 으로 동시 호출 시 "release already exists"
// 또는 partial state 발생.
//
// per-key mutex pattern: releaseLocks map 이 string key → *sync.Mutex 보관. 동일 key 의 모든
// 호출자가 같은 mutex 에 await. key 는 "namespace/release" — 다른 namespace 의 같은 이름은 OK.
//
// releaseLocks lifetime 관리: entry 가 영구 잔존하면
// dynamic release 이름 (tenant-prefixed, build-id 등) 환경에서 무한 증가 위험.
//
// 새 entry 구조: lock + lastUsedAt. uninstall 성공 시 즉시 삭제, 그 외 동작은 cleanupLoop 가 TTL
// 경과한 entry 를 주기 정리. cleanup interval / TTL 은 보수적 (30분 idle = 정리). install 후 다른
// op 가 같은 release 에 들어오면 cleanup 전 last-used 갱신 → 활성 release 의 lock 은 정리 안 됨.
type releaseLock struct {
	mu         sync.Mutex
	lastUsedAt int64 // unix nano
}

var (
	releaseLocks   = make(map[string]*releaseLock)
	releaseLocksMu sync.Mutex
	// cleanup 주기. 30분 idle entry 제거. 0 이면 정리 안 함 (test 가 disable 가능).
	releaseLockTTL    = 30 * time.Minute
	cleanupOnce       sync.Once
)

// acquireReleaseLock — namespace/release 의 mutex 를 반환 (없으면 생성). 호출자는 즉시 Lock().
// install/upgrade/uninstall 의 진입점에서 acquireReleaseLock(...).Lock() / defer Unlock() 사용.
func acquireReleaseLock(namespace, release string) *sync.Mutex {
	startReleaseLockCleanupOnce()
	key := namespace + "/" + release
	releaseLocksMu.Lock()
	defer releaseLocksMu.Unlock()
	entry, ok := releaseLocks[key]
	if !ok {
		entry = &releaseLock{}
		releaseLocks[key] = entry
	}
	entry.lastUsedAt = time.Now().UnixNano()
	return &entry.mu
}

// dropReleaseLock — uninstall 성공 직후 호출. entry 제거. 다음 install 시 새 lock 생성 OK.
func dropReleaseLock(namespace, release string) {
	key := namespace + "/" + release
	releaseLocksMu.Lock()
	defer releaseLocksMu.Unlock()
	delete(releaseLocks, key)
}

// startReleaseLockCleanupOnce — 첫 acquireReleaseLock 호출 시 background goroutine 시작. TTL 초과한
// entry 주기 정리. 단일 instance — 여러 호출 idempotent.
func startReleaseLockCleanupOnce() {
	cleanupOnce.Do(func() {
		if releaseLockTTL <= 0 {
			return
		}
		go func() {
			tick := time.NewTicker(releaseLockTTL / 2)
			defer tick.Stop()
			for range tick.C {
				cutoff := time.Now().Add(-releaseLockTTL).UnixNano()
				releaseLocksMu.Lock()
				for k, e := range releaseLocks {
					// Mutex 가 locked 인지 확인 불가 — lastUsedAt 만으로 판단. install 진행 중인 entry 는
					// lastUsedAt 가 막 갱신됐으므로 TTL 미만 → 정리 안 됨. 진행 후엔 다음 cycle 에 정리.
					if e.lastUsedAt < cutoff {
						delete(releaseLocks, k)
					}
				}
				releaseLocksMu.Unlock()
			}
		}()
	})
}

func (d *Dispatcher) installAddon(ctx context.Context, cmd *agentv1.CommandRequest) *agentv1.CommandResponse {
	if d.helm == nil {
		return errorResponse(agentv1.Status_AGENT_UNAVAILABLE, "HELM_CLIENT_NIL", "Helm client not initialized")
	}
	if d.allowlist == nil {
		return errorResponse(agentv1.Status_PERMISSION_DENIED, "ALLOWLIST_REQUIRED", "AllowList not loaded")
	}
	chartRef := getStringParam(cmd, "chart") // "repo/name" 형식.
	// backend 가 helm_repo table 에서 미리 lookup 한 URL. 명시 시 helm SDK 의
	// alias 검색 우회 — chart-museum-external 같이 agent ~/.config/helm/repositories.yaml 에
	// 등록 안 된 backend-managed repo 도 정상 install.
	repoUrlParam := getStringParam(cmd, "repoUrl")
	// backend 가 chart .tgz 를 pre-fetch 해서 base64 로 push. agent 가 chartmuseum
	// 도달 불가한 air-gapped / 사내망 환경의 1순위 경로. 명시 시 URL fetch 우회.
	chartTarballB64 := getStringParam(cmd, "chartTarballBase64")
	version := getStringParam(cmd, "version")
	namespace := getStringParam(cmd, "namespace")
	releaseName := getStringParam(cmd, "release")
	if chartRef == "" || version == "" || namespace == "" || releaseName == "" {
		return errorResponse(agentv1.Status_INVALID_PARAMS, "MISSING_PARAM",
			"chart, version, namespace, release required")
	}
	repo, chart, splitErr := splitChartRef(chartRef)
	if splitErr != nil {
		return errorResponse(agentv1.Status_INVALID_PARAMS, "INVALID_CHART_REF", splitErr.Error())
	}

	// install concurrency lock. 같은 (namespace, release) 의 동시 install
	// race 차단. helm SDK 가 serialize 안 하므로 두 caller 가 동시 진입 시 "release already exists"
	// 또는 partial state 발생 가능.
	lock := acquireReleaseLock(namespace, releaseName)
	lock.Lock()
	defer lock.Unlock()

	// AllowList 통과 검사 — namespace + chart rule.
	policy := d.allowlist.Snapshot()
	if !policy.IsNamespaceAllowed(namespace) {
		return errorResponse(agentv1.Status_PERMISSION_DENIED, "NAMESPACE_NOT_ALLOWED",
			fmt.Sprintf("namespace %s not in allowlist", namespace))
	}
	rule := policy.FindChartRule(repo, chart)
	if rule == nil {
		return errorResponse(agentv1.Status_PERMISSION_DENIED, "CHART_NOT_ALLOWED",
			fmt.Sprintf("chart %s/%s not in allowlist", repo, chart))
	}
	if !versionInRange(version, rule.MinVersion, rule.MaxVersion) {
		return errorResponse(agentv1.Status_PERMISSION_DENIED, "VERSION_OUT_OF_RANGE",
			fmt.Sprintf("version %s outside [%s, %s] for %s/%s",
				version, rule.MinVersion, rule.MaxVersion, repo, chart))
	}

	// values JSON string (optional).
	var values map[string]interface{}
	if valsRaw := getStringParam(cmd, "values"); valsRaw != "" {
		if err := jsonUnmarshal(valsRaw, &values); err != nil {
			return errorResponse(agentv1.Status_INVALID_PARAMS, "INVALID_VALUES", err.Error())
		}
	}

	// chart tarball 이 backend 에서 push 됐으면 temp file 로 풀어서 LocalChartPath 로
	// 전달. agent 가 chartmuseum 등 외부 URL 에 접근할 필요 없음.
	var localChartPath string
	if chartTarballB64 != "" {
		raw, decodeErr := base64.StdEncoding.DecodeString(chartTarballB64)
		if decodeErr != nil {
			return errorResponse(agentv1.Status_INVALID_PARAMS, "INVALID_CHART_TARBALL",
				"chartTarballBase64 decode 실패: "+decodeErr.Error())
		}
		tmpFile, writeErr := os.CreateTemp("", "chart-*.tgz")
		if writeErr != nil {
			return errorResponse(agentv1.Status_FAILED, "TEMPFILE_FAILED",
				"chart tarball 쓰기 실패: "+writeErr.Error())
		}
		if _, writeErr = tmpFile.Write(raw); writeErr != nil {
			_ = tmpFile.Close()
			_ = os.Remove(tmpFile.Name())
			return errorResponse(agentv1.Status_FAILED, "TEMPFILE_FAILED",
				"chart tarball 쓰기 실패: "+writeErr.Error())
		}
		_ = tmpFile.Close()
		defer os.Remove(tmpFile.Name())
		localChartPath = tmpFile.Name()
		slog.Info("agent loaded chart from backend-pushed tarball",
			slog.Int("bytes", len(raw)), slog.String("path", localChartPath))
	}

	rel, err := d.helm.Install(ctx, helm.InstallOptions{
		ReleaseName:     releaseName,
		Namespace:       namespace,
		Repo:            repo,
		RepoURL:         repoUrlParam,   // backend lookup 값. 빈 값이면 alias fallback.
		LocalChartPath:  localChartPath, // backend push 의 1순위 path. 명시 시 URL 우회.
		Chart:           chart,
		Version:         version,
		Values:          values,
		CreateNamespace: parseBool(getStringParam(cmd, "createNamespace")),
		Timeout:         5 * time.Minute,
	})
	if err != nil {
		return errorResponse(agentv1.Status_FAILED, "HELM_INSTALL_FAILED", err.Error())
	}
	result, _ := structpb.NewStruct(map[string]interface{}{
		"release":           rel.Name,
		"namespace":         rel.Namespace,
		"chart":             rel.Chart,
		"version":           rel.Version,
		"revision":          float64(rel.Revision),
		"status":            rel.Status,
		"agent_instance_id": d.agentInstanceID,
	})
	return okResponse(result)
}

// upgradeAddon — 기존 release 를 새 chart version / values 로 업그레이드.
// installAddon 과 거의 동일 logic — chart resolution + allowlist 검증 + release lock + helm SDK Upgrade.
// release 미존재 시 helm SDK 가 error 반환 → caller 가 먼저 install 안내.
func (d *Dispatcher) upgradeAddon(ctx context.Context, cmd *agentv1.CommandRequest) *agentv1.CommandResponse {
	if d.helm == nil {
		return errorResponse(agentv1.Status_AGENT_UNAVAILABLE, "HELM_CLIENT_NIL", "Helm client not initialized")
	}
	if d.allowlist == nil {
		return errorResponse(agentv1.Status_PERMISSION_DENIED, "ALLOWLIST_REQUIRED", "AllowList not loaded")
	}
	chartRef := getStringParam(cmd, "chart")
	repoUrlParam := getStringParam(cmd, "repoUrl")
	chartTarballB64 := getStringParam(cmd, "chartTarballBase64")
	version := getStringParam(cmd, "version")
	namespace := getStringParam(cmd, "namespace")
	releaseName := getStringParam(cmd, "release")
	if chartRef == "" || version == "" || namespace == "" || releaseName == "" {
		return errorResponse(agentv1.Status_INVALID_PARAMS, "MISSING_PARAM",
			"chart, version, namespace, release required")
	}
	repo, chart, splitErr := splitChartRef(chartRef)
	if splitErr != nil {
		return errorResponse(agentv1.Status_INVALID_PARAMS, "INVALID_CHART_REF", splitErr.Error())
	}

	// release lock (install 과 공유).
	lock := acquireReleaseLock(namespace, releaseName)
	lock.Lock()
	defer lock.Unlock()

	// AllowList — install 과 동일.
	policy := d.allowlist.Snapshot()
	if !policy.IsNamespaceAllowed(namespace) {
		return errorResponse(agentv1.Status_PERMISSION_DENIED, "NAMESPACE_NOT_ALLOWED",
			fmt.Sprintf("namespace %s not in allowlist", namespace))
	}
	rule := policy.FindChartRule(repo, chart)
	if rule == nil {
		return errorResponse(agentv1.Status_PERMISSION_DENIED, "CHART_NOT_ALLOWED",
			fmt.Sprintf("chart %s/%s not in allowlist", repo, chart))
	}
	if !versionInRange(version, rule.MinVersion, rule.MaxVersion) {
		return errorResponse(agentv1.Status_PERMISSION_DENIED, "VERSION_OUT_OF_RANGE",
			fmt.Sprintf("version %s outside range %s-%s", version, rule.MinVersion, rule.MaxVersion))
	}

	// Chart tarball (선택) → temp file. install path 와 동일.
	var localChartPath string
	if chartTarballB64 != "" {
		decoded, err := base64.StdEncoding.DecodeString(chartTarballB64)
		if err != nil {
			return errorResponse(agentv1.Status_INVALID_PARAMS, "INVALID_TARBALL",
				fmt.Sprintf("base64 decode: %s", err.Error()))
		}
		f, err := os.CreateTemp("", "chart-*.tgz")
		if err != nil {
			return errorResponse(agentv1.Status_FAILED, "TEMPFILE_FAILED", err.Error())
		}
		localChartPath = f.Name()
		defer os.Remove(localChartPath)
		if _, err := f.Write(decoded); err != nil {
			f.Close()
			return errorResponse(agentv1.Status_FAILED, "TEMPFILE_WRITE_FAILED", err.Error())
		}
		f.Close()
	}

	// values JSON string (optional) — installAddon 과 동일 패턴.
	var values map[string]interface{}
	if valsRaw := getStringParam(cmd, "values"); valsRaw != "" {
		if err := jsonUnmarshal(valsRaw, &values); err != nil {
			return errorResponse(agentv1.Status_INVALID_PARAMS, "INVALID_VALUES", err.Error())
		}
	}
	atomic := parseBool(getStringParam(cmd, "atomic"))
	reuseValues := parseBool(getStringParam(cmd, "reuseValues"))
	resetValues := parseBool(getStringParam(cmd, "resetValues"))
	timeout := 10 * time.Minute // upgrade 는 install 보다 약간 더 길게 default (보통 db migration hook).
	if t := getStringParam(cmd, "timeout"); t != "" {
		if s, err := strconv.Atoi(t); err == nil && s > 0 {
			timeout = time.Duration(s) * time.Second
		}
	}

	rel, err := d.helm.Upgrade(ctx, helm.UpgradeOptions{
		ReleaseName:    releaseName,
		Namespace:      namespace,
		Repo:           repo,
		RepoURL:        repoUrlParam,
		LocalChartPath: localChartPath,
		Chart:          chart,
		Version:        version,
		Values:         values,
		Timeout:        timeout,
		Atomic:         atomic,
		ReuseValues:    reuseValues,
		ResetValues:    resetValues,
	})
	if err != nil {
		// helm SDK 의 "release: not found" 패턴을 별도 error_code 로 분류.
		errMsg := err.Error()
		if strings.Contains(errMsg, "not found") || strings.Contains(errMsg, "release: not found") {
			return errorResponse(agentv1.Status_FAILED, "HELM_NOT_FOUND",
				fmt.Sprintf("release %s not installed — call INSTALL_ADDON first", releaseName))
		}
		return errorResponse(agentv1.Status_FAILED, "HELM_UPGRADE_FAILED", errMsg)
	}

	result, _ := structpb.NewStruct(map[string]interface{}{
		"release":           rel.Name,
		"namespace":         rel.Namespace,
		"chart":             rel.Chart,
		"version":           rel.Version,
		"revision":          float64(rel.Revision),
		"status":            rel.Status,
		"updated":           rel.Updated.Format(time.RFC3339),
		"agent_instance_id": d.agentInstanceID,
	})
	return okResponse(result)
}

func (d *Dispatcher) uninstallAddon(ctx context.Context, cmd *agentv1.CommandRequest) *agentv1.CommandResponse {
	if d.helm == nil {
		return errorResponse(agentv1.Status_AGENT_UNAVAILABLE, "HELM_CLIENT_NIL", "Helm client not initialized")
	}
	namespace := getStringParam(cmd, "namespace")
	releaseName := getStringParam(cmd, "release")
	if namespace == "" || releaseName == "" {
		return errorResponse(agentv1.Status_INVALID_PARAMS, "MISSING_PARAM", "namespace, release required")
	}
	if d.allowlist != nil && !d.allowlist.Snapshot().IsNamespaceAllowed(namespace) {
		return errorResponse(agentv1.Status_PERMISSION_DENIED, "NAMESPACE_NOT_ALLOWED",
			fmt.Sprintf("namespace %s not in allowlist", namespace))
	}

	// uninstall 도 동일 release lock.
	// install / upgrade / uninstall 가 같은 mutex 공유 — 한 release 의 lifecycle ops 직렬화.
	lock := acquireReleaseLock(namespace, releaseName)
	lock.Lock()
	defer lock.Unlock()

	// backend 의 ChartService.uninstallRelease 가 보내는 옵션.
	// 누락 / 잘못된 문자열은 안전한 default (false) 로 떨어짐 — 기존 caller 호환.
	keepHistory := parseBool(getStringParam(cmd, "keepHistory"))
	wait := parseBool(getStringParam(cmd, "wait"))
	if err := d.helm.Uninstall(ctx, helm.UninstallOptions{
		ReleaseName: releaseName,
		Namespace:   namespace,
		KeepHistory: keepHistory,
		Wait:        wait,
	}); err != nil {
		return errorResponse(agentv1.Status_FAILED, "HELM_UNINSTALL_FAILED", err.Error())
	}
	// uninstall 성공 직후 release lock entry 즉시 제거. 다음 install 은
	// 새 lock 생성. 영구 증가 누수 방지의 핵심 path. KeepHistory=true 여도 release lifecycle
	// 자체는 종료된 셈 → entry 제거 안전 (다음 op 가 들어오면 새 entry 생성).
	dropReleaseLock(namespace, releaseName)
	result, _ := structpb.NewStruct(map[string]interface{}{
		"release":           releaseName,
		"namespace":         namespace,
		"keepHistory":       keepHistory,
		"wait":              wait,
		"agent_instance_id": d.agentInstanceID,
	})
	return okResponse(result)
}

func (d *Dispatcher) listHelmReleases(ctx context.Context, cmd *agentv1.CommandRequest) *agentv1.CommandResponse {
	if d.helm == nil {
		return errorResponse(agentv1.Status_AGENT_UNAVAILABLE, "HELM_CLIENT_NIL", "Helm client not initialized")
	}
	namespace := getStringParam(cmd, "namespace")
	if namespace == "_all" {
		namespace = ""
	}
	rels, err := d.helm.List(ctx, namespace)
	if err != nil {
		return errorResponse(agentv1.Status_FAILED, "HELM_LIST_FAILED", err.Error())
	}
	relList := make([]interface{}, 0, len(rels))
	for _, r := range rels {
		relList = append(relList, map[string]interface{}{
			"name": r.Name, "namespace": r.Namespace,
			"chart": r.Chart, "version": r.Version, "app_version": r.AppVersion,
			"revision": float64(r.Revision), "status": r.Status,
			"updated": r.Updated.Format(time.RFC3339),
		})
	}
	result, _ := structpb.NewStruct(map[string]interface{}{
		"namespace":         namespace,
		"count":             float64(len(rels)),
		"releases":          relList,
		"agent_instance_id": d.agentInstanceID,
	})
	return okResponse(result)
}

// getHelmReleaseStatus — 단일 release 의 status. backend 의 ChartServiceImpl.getChartStatus
// 가 helm CLI + kubeconfig fall-through 대신 사용. helm SDK 의 action.NewStatus 한 번 호출.
//
// AllowList — namespace 검증만 (read-only 명령). chart-level 검증은 install 에서만.
func (d *Dispatcher) getHelmReleaseStatus(ctx context.Context, cmd *agentv1.CommandRequest) *agentv1.CommandResponse {
	if d.helm == nil {
		return errorResponse(agentv1.Status_AGENT_UNAVAILABLE, "HELM_CLIENT_NIL", "Helm client not initialized")
	}
	namespace := getStringParam(cmd, "namespace")
	release := getStringParam(cmd, "release")
	if namespace == "" || release == "" {
		return errorResponse(agentv1.Status_INVALID_PARAMS, "MISSING_PARAM", "namespace, release required")
	}
	if d.allowlist != nil && !d.allowlist.Snapshot().IsNamespaceAllowed(namespace) {
		return errorResponse(agentv1.Status_PERMISSION_DENIED, "NAMESPACE_NOT_ALLOWED",
			fmt.Sprintf("namespace %s not in allowlist", namespace))
	}
	rel, err := d.helm.Status(ctx, namespace, release)
	if err != nil {
		// helm SDK 의 driver.ErrReleaseNotFound 는 error message 가 "release: not found" — distinguishable.
		code := "HELM_STATUS_FAILED"
		if strings.Contains(err.Error(), "not found") {
			code = "HELM_NOT_FOUND"
		}
		return errorResponse(agentv1.Status_FAILED, code, err.Error())
	}
	result, _ := structpb.NewStruct(map[string]interface{}{
		"name":              rel.Name,
		"namespace":         rel.Namespace,
		"chart":             rel.Chart,
		"version":           rel.Version,
		"app_version":       rel.AppVersion,
		"revision":          float64(rel.Revision),
		"status":            rel.Status,
		"updated":           rel.Updated.Format(time.RFC3339),
		"agent_instance_id": d.agentInstanceID,
	})
	return okResponse(result)
}

// getHelmReleaseHistory — release 의 revision 이력. backend 의 ChartServiceImpl.getReleaseHistory
// 가 helm CLI + kubeconfig fall-through 대신 사용. helm SDK 의 action.NewHistory.
//
// AllowList — namespace 검증만 (read-only).
func (d *Dispatcher) getHelmReleaseHistory(ctx context.Context, cmd *agentv1.CommandRequest) *agentv1.CommandResponse {
	if d.helm == nil {
		return errorResponse(agentv1.Status_AGENT_UNAVAILABLE, "HELM_CLIENT_NIL", "Helm client not initialized")
	}
	namespace := getStringParam(cmd, "namespace")
	release := getStringParam(cmd, "release")
	if namespace == "" || release == "" {
		return errorResponse(agentv1.Status_INVALID_PARAMS, "MISSING_PARAM", "namespace, release required")
	}
	if d.allowlist != nil && !d.allowlist.Snapshot().IsNamespaceAllowed(namespace) {
		return errorResponse(agentv1.Status_PERMISSION_DENIED, "NAMESPACE_NOT_ALLOWED",
			fmt.Sprintf("namespace %s not in allowlist", namespace))
	}
	max := int(parseInt64(getStringParam(cmd, "max"), 0))
	revs, err := d.helm.History(ctx, namespace, release, max)
	if err != nil {
		code := "HELM_HISTORY_FAILED"
		if strings.Contains(err.Error(), "not found") {
			code = "HELM_NOT_FOUND"
		}
		return errorResponse(agentv1.Status_FAILED, code, err.Error())
	}
	revList := make([]interface{}, 0, len(revs))
	for _, r := range revs {
		revList = append(revList, map[string]interface{}{
			"revision":    float64(r.Revision),
			"updated":     r.Updated.Format(time.RFC3339),
			"status":      r.Status,
			"chart":       r.Chart,
			"app_version": r.AppVersion,
			"description": r.Description,
		})
	}
	result, _ := structpb.NewStruct(map[string]interface{}{
		"namespace":         namespace,
		"release":           release,
		"revisions":         revList,
		"count":             float64(len(revs)),
		"agent_instance_id": d.agentInstanceID,
	})
	return okResponse(result)
}

// rollbackHelmRelease — release 를 지정 revision 으로 복원. backend 의 ChartServiceImpl.rollbackRelease
// 의 agent-side path. helm SDK action.NewRollback + 후속 status 호출.
//
// AllowList — namespace 검증만 (chart-level 은 install 에서만).
func (d *Dispatcher) rollbackHelmRelease(ctx context.Context, cmd *agentv1.CommandRequest) *agentv1.CommandResponse {
	if d.helm == nil {
		return errorResponse(agentv1.Status_AGENT_UNAVAILABLE, "HELM_CLIENT_NIL", "Helm client not initialized")
	}
	namespace := getStringParam(cmd, "namespace")
	release := getStringParam(cmd, "release")
	if namespace == "" || release == "" {
		return errorResponse(agentv1.Status_INVALID_PARAMS, "MISSING_PARAM", "namespace, release required")
	}
	if d.allowlist != nil && !d.allowlist.Snapshot().IsNamespaceAllowed(namespace) {
		return errorResponse(agentv1.Status_PERMISSION_DENIED, "NAMESPACE_NOT_ALLOWED",
			fmt.Sprintf("namespace %s not in allowlist", namespace))
	}
	revision := int(parseInt64(getStringParam(cmd, "revision"), 0))
	wait := parseBool(getStringParam(cmd, "wait"))
	rel, err := d.helm.Rollback(ctx, helm.RollbackOptions{
		ReleaseName: release,
		Namespace:   namespace,
		Revision:    revision,
		Wait:        wait,
	})
	if err != nil {
		code := "HELM_ROLLBACK_FAILED"
		if strings.Contains(err.Error(), "not found") {
			code = "HELM_NOT_FOUND"
		}
		return errorResponse(agentv1.Status_FAILED, code, err.Error())
	}
	result, _ := structpb.NewStruct(map[string]interface{}{
		"name":              rel.Name,
		"namespace":         rel.Namespace,
		"chart":             rel.Chart,
		"version":           rel.Version,
		"revision":          float64(rel.Revision),
		"status":            rel.Status,
		"updated":           rel.Updated.Format(time.RFC3339),
		"agent_instance_id": d.agentInstanceID,
	})
	return okResponse(result)
}

// listHelmReleaseResources — helm release (label app.kubernetes.io/instance=<release>) 가 만든
// K8s 자원을 한 번에 enumerate. backend 의 HelmReleaseScanner (fabric8 11 호출) 의 agent 측 대체.
//
// AllowList — namespace 만 검증 (read-only 명령 — chart-level 검증 불요).
func (d *Dispatcher) listHelmReleaseResources(ctx context.Context, cmd *agentv1.CommandRequest) *agentv1.CommandResponse {
	if d.kube == nil {
		return errorResponse(agentv1.Status_AGENT_UNAVAILABLE, "K8S_CLIENT_NIL", "K8s client not initialized")
	}
	namespace := getStringParam(cmd, "namespace")
	release := getStringParam(cmd, "release")
	if release == "" {
		return errorResponse(agentv1.Status_INVALID_PARAMS, "MISSING_PARAM", "release required")
	}
	if d.allowlist != nil && namespace != "" && !d.allowlist.Snapshot().IsNamespaceAllowed(namespace) {
		return errorResponse(agentv1.Status_PERMISSION_DENIED, "NAMESPACE_NOT_ALLOWED",
			fmt.Sprintf("namespace %s not in allowlist", namespace))
	}

	refs, err := d.kube.ListByHelmRelease(ctx, k8s.HelmReleaseResourcesOptions{
		Namespace: namespace,
		Release:   release,
	})
	if err != nil {
		return errorResponse(agentv1.Status_FAILED, "K8S_LIST_FAILED", err.Error())
	}
	items := make([]interface{}, 0, len(refs))
	for _, r := range refs {
		items = append(items, map[string]interface{}{
			"kind":       r.Kind,
			"apiVersion": r.APIVersion,
			"namespace":  r.Namespace,
			"name":       r.Name,
		})
	}
	result, _ := structpb.NewStruct(map[string]interface{}{
		"namespace":         namespace,
		"release":           release,
		"count":             float64(len(refs)),
		"items":             items,
		"agent_instance_id": d.agentInstanceID,
	})
	return okResponse(result)
}
