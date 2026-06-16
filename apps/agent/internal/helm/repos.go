// Hybrid helm-repo sync.
//
// Backend 가 APPLY_AGENT_CONFIG 의 helm_repositories param 으로 보낸 list 를 agent 의 helm SDK
// RepositoryFile (~/.config/helm/repositories.yaml or settings.RepositoryConfig path) 에 sync.
//
// 동작:
//   1) 입력 list (RepoEntry) 를 받아 helm SDK 의 repo.Entry 로 변환.
//   2) 기존 RepositoryFile load. 없으면 new file.
//   3) 새 list 와 기존 file 의 다른 entry 모두 보존 (사용자가 helm CLI 로 직접 추가한 repo 보호).
//      → 그러나 같은 name 의 entry 는 backend list 가 win (overwrite).
//   4) WriteFile.
//
// 본 sync 는 best-effort — IO 실패는 caller (apply_config) 가 swallow. ConfigMap 자체는 항상
// 최신 (single source of truth), agent restart 시 boot loader 가 ConfigMap 의 helm_repositories
// key 보고 다시 sync.

package helm

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"strings"

	"helm.sh/helm/v3/pkg/cli"
	"helm.sh/helm/v3/pkg/repo"
)

// RepoEntry — backend JSON 의 한 entry. helm SDK 의 repo.Entry 와 1:1.
//
//   { "name": "...", "url": "...", "username": "...", "password": "...",
//     "ca_file": "...", "insecure_skip_tls_verify": false }
//
// 모든 field 는 omitempty — backend 가 빈 값을 보내면 빈 문자열.
type RepoEntry struct {
	Name                  string `json:"name"`
	URL                   string `json:"url"`
	Username              string `json:"username,omitempty"`
	Password              string `json:"password,omitempty"`
	CAFile                string `json:"ca_file,omitempty"`
	InsecureSkipTLSverify bool   `json:"insecure_skip_tls_verify,omitempty"`
}

// ParseRepoList — JSON array string 을 []RepoEntry 로. 빈 문자열은 빈 slice + nil error.
//
// Malformed JSON 은 error wrap.
func ParseRepoList(raw string) ([]RepoEntry, error) {
	if raw == "" {
		return nil, nil
	}
	var out []RepoEntry
	if err := json.Unmarshal([]byte(raw), &out); err != nil {
		return nil, fmt.Errorf("helm_repositories: not a JSON array of objects (%v)", err)
	}
	return out, nil
}

// SyncRepositories — backend list 를 helm SDK 의 RepositoryFile 에 merge.
//
// 동작:
//   - 입력 list 의 name 과 같은 기존 entry 는 새 값으로 update (overwrite).
//   - 입력 list 에 없는 기존 entry 는 보존 (사용자가 직접 helm repo add 한 것).
//   - settings 의 RepositoryConfig path 가 없으면 새로 만들어 write.
//
// orphan removal 은 {@link SyncRepositoriesWithCleanup} 사용.
func SyncRepositories(settings *cli.EnvSettings, repos []RepoEntry) (int, error) {
	if settings == nil {
		return 0, fmt.Errorf("helm settings is nil")
	}
	if len(repos) == 0 {
		// 빈 array — caller 가 명시적으로 비웠을 수 있음. file 자체는 건드리지 않음.
		return 0, nil
	}

	// 1) 기존 file load (없어도 OK — fresh file 로 시작).
	cfgPath := settings.RepositoryConfig
	if cfgPath == "" {
		return 0, fmt.Errorf("helm settings.RepositoryConfig is empty")
	}
	// dir 보장
	if err := os.MkdirAll(filepath.Dir(cfgPath), 0o755); err != nil {
		return 0, fmt.Errorf("mkdir for repo config: %w", err)
	}

	rf, err := repo.LoadFile(cfgPath)
	if err != nil {
		// not-exist 도 LoadFile 에서 error. 그 경우 새 file.
		rf = repo.NewFile()
	}

	// 2) merge — 같은 name 은 새 entry 가 win.
	count := 0
	for _, r := range repos {
		if r.Name == "" || r.URL == "" {
			continue     // skip invalid
		}
		e := &repo.Entry{
			Name:                  r.Name,
			URL:                   r.URL,
			Username:              r.Username,
			Password:              r.Password,
			CAFile:                r.CAFile,
			InsecureSkipTLSverify: r.InsecureSkipTLSverify,
		}
		// repo.File.Update — 동명 entry overwrite, 없으면 append.
		rf.Update(e)
		count++
	}

	// 3) write
	if err := rf.WriteFile(cfgPath, 0o644); err != nil {
		return count, fmt.Errorf("write repo config: %w", err)
	}
	return count, nil
}

// AnycloudManagedRepoPrefix — backend 에서 push 된 repo 의 식별자. agent 는 본 prefix 가
// name 에 붙은 경우만 orphan 으로 판단 (사용자가 helm repo add 직접 한 것은 보호).
//
// 사용자가 비슷한 이름으로 명명 충돌하면 안 됨 — 운영 가이드에서 명시.
const AnycloudManagedRepoPrefix = "anycloud-"

// SyncRepositoriesWithCleanup — backend list 와 RepositoryFile 의 차이를 reconcile.
//
// 동작은 SyncRepositories 와 동일하되, 추가로:
//   - 기존 RepositoryFile 의 entry 중 입력 list 에 없으면서 anycloud-managed 마커가 붙은 것을 제거.
//   - "anycloud-managed" 판정: 현재는 단순 prefix ({@link AnycloudManagedRepoPrefix}) — 향후
//     별도 metadata 컬럼 또는 별도 lookup file 로 확장 가능.
//
// 입력 list 가 비면 (backend 가 모든 repo 제거 의도) anycloud-managed entry 만 전부 제거 +
// 사용자 수동 추가 entry 는 보존.
//
// Caller (apply_config) 가 본 함수와 SyncRepositories 중 선택 — orphan cleanup 옵션 명시 위해.
func SyncRepositoriesWithCleanup(settings *cli.EnvSettings, repos []RepoEntry) (added, removed int, err error) {
	if settings == nil {
		return 0, 0, fmt.Errorf("helm settings is nil")
	}
	cfgPath := settings.RepositoryConfig
	if cfgPath == "" {
		return 0, 0, fmt.Errorf("helm settings.RepositoryConfig is empty")
	}
	if e := os.MkdirAll(filepath.Dir(cfgPath), 0o755); e != nil {
		return 0, 0, fmt.Errorf("mkdir for repo config: %w", e)
	}
	rf, e := repo.LoadFile(cfgPath)
	if e != nil {
		rf = repo.NewFile()
	}

	// desired name set (anycloud-managed name 들).
	desired := make(map[string]struct{}, len(repos))
	for _, r := range repos {
		if r.Name == "" || r.URL == "" {
			continue
		}
		desired[r.Name] = struct{}{}
	}

	// 1) 기존 anycloud-managed entry 중 desired 에 없는 것을 제거.
	keep := make([]*repo.Entry, 0, len(rf.Repositories))
	for _, e := range rf.Repositories {
		if strings.HasPrefix(e.Name, AnycloudManagedRepoPrefix) {
			if _, ok := desired[e.Name]; !ok {
				removed++
				continue     // skip — orphan
			}
		}
		keep = append(keep, e)
	}
	rf.Repositories = keep

	// 2) 새 list 의 entry add/update.
	for _, r := range repos {
		if r.Name == "" || r.URL == "" {
			continue
		}
		rf.Update(&repo.Entry{
			Name:                  r.Name,
			URL:                   r.URL,
			Username:              r.Username,
			Password:              r.Password,
			CAFile:                r.CAFile,
			InsecureSkipTLSverify: r.InsecureSkipTLSverify,
		})
		added++
	}

	if e := rf.WriteFile(cfgPath, 0o644); e != nil {
		return added, removed, fmt.Errorf("write repo config: %w", e)
	}
	return added, removed, nil
}
