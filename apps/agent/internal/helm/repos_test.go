// helm.SyncRepositories / SyncRepositoriesWithCleanup 검증.
//
// fake EnvSettings 의 RepositoryConfig 를 tmp dir 의 경로로 설정 후 actual helm SDK 의
// repo.LoadFile / WriteFile 호출. helm internal 은 mock 없이 진짜 실행.

package helm

import (
	"os"
	"path/filepath"
	"testing"

	"helm.sh/helm/v3/pkg/cli"
	"helm.sh/helm/v3/pkg/repo"
)

func newTestSettings(t *testing.T) *cli.EnvSettings {
	t.Helper()
	dir := t.TempDir()
	s := cli.New()
	s.RepositoryConfig = filepath.Join(dir, "repositories.yaml")
	s.RepositoryCache = filepath.Join(dir, "cache")
	return s
}

func TestParseRepoList_Empty(t *testing.T) {
	out, err := ParseRepoList("")
	if err != nil {
		t.Fatalf("empty raw: %v", err)
	}
	if len(out) != 0 {
		t.Fatalf("empty raw should yield empty slice, got %d", len(out))
	}
}

func TestParseRepoList_Malformed(t *testing.T) {
	_, err := ParseRepoList("{not a json array}")
	if err == nil {
		t.Fatalf("expected error on malformed JSON")
	}
}

func TestParseRepoList_Valid(t *testing.T) {
	raw := `[{"name":"prom","url":"https://prom.io","insecure_skip_tls_verify":true}]`
	out, err := ParseRepoList(raw)
	if err != nil {
		t.Fatalf("parse: %v", err)
	}
	if len(out) != 1 {
		t.Fatalf("expected 1 entry, got %d", len(out))
	}
	if out[0].Name != "prom" || out[0].URL != "https://prom.io" || !out[0].InsecureSkipTLSverify {
		t.Fatalf("entry mismatch: %+v", out[0])
	}
}

func TestSyncRepositories_EmptyList_NoOp(t *testing.T) {
	s := newTestSettings(t)
	n, err := SyncRepositories(s, nil)
	if err != nil {
		t.Fatalf("nil list: %v", err)
	}
	if n != 0 {
		t.Fatalf("expected 0 added, got %d", n)
	}
	// 빈 list 라 RepositoryConfig 가 생성 안 돼야 (no-op).
	if _, e := os.Stat(s.RepositoryConfig); !os.IsNotExist(e) {
		t.Fatalf("expected no file write on empty list, but file exists or other err: %v", e)
	}
}

func TestSyncRepositories_AddNew(t *testing.T) {
	s := newTestSettings(t)
	repos := []RepoEntry{
		{Name: "prom", URL: "https://prom.io"},
		{Name: "grafana", URL: "https://grafana.io"},
	}
	n, err := SyncRepositories(s, repos)
	if err != nil {
		t.Fatalf("sync: %v", err)
	}
	if n != 2 {
		t.Fatalf("expected 2 added, got %d", n)
	}
	rf, err := repo.LoadFile(s.RepositoryConfig)
	if err != nil {
		t.Fatalf("load after sync: %v", err)
	}
	if len(rf.Repositories) != 2 {
		t.Fatalf("RepositoryFile expected 2 entries, got %d", len(rf.Repositories))
	}
}

func TestSyncRepositoriesWithCleanup_OrphanRemoval(t *testing.T) {
	s := newTestSettings(t)
	// Pre-seed: 1 anycloud-managed (will be orphan), 1 user-manual entry.
	pre := []RepoEntry{
		{Name: "anycloud-old", URL: "https://old.example.com"},
		{Name: "user-custom", URL: "https://custom.example.com"},
	}
	if _, err := SyncRepositories(s, pre); err != nil {
		t.Fatalf("pre-seed: %v", err)
	}

	// New desired: 1 new anycloud-managed entry. old 는 사라져야, user-custom 은 보존되어야.
	desired := []RepoEntry{
		{Name: "anycloud-new", URL: "https://new.example.com"},
	}
	added, removed, err := SyncRepositoriesWithCleanup(s, desired)
	if err != nil {
		t.Fatalf("cleanup sync: %v", err)
	}
	if added != 1 {
		t.Errorf("expected 1 added, got %d", added)
	}
	if removed != 1 {
		t.Errorf("expected 1 removed (anycloud-old), got %d", removed)
	}

	rf, err := repo.LoadFile(s.RepositoryConfig)
	if err != nil {
		t.Fatalf("load after cleanup: %v", err)
	}
	names := map[string]bool{}
	for _, e := range rf.Repositories {
		names[e.Name] = true
	}
	if !names["anycloud-new"] {
		t.Error("anycloud-new missing after sync")
	}
	if !names["user-custom"] {
		t.Error("user-custom 가 보호되지 않음 — orphan cleanup 이 사용자 entry 까지 제거")
	}
	if names["anycloud-old"] {
		t.Error("anycloud-old 가 cleanup 안 됨 — orphan removal 미동작")
	}
}

func TestSyncRepositoriesWithCleanup_EmptyDesired_RemovesAllAnycloudManaged(t *testing.T) {
	s := newTestSettings(t)
	pre := []RepoEntry{
		{Name: "anycloud-a", URL: "https://a.example.com"},
		{Name: "anycloud-b", URL: "https://b.example.com"},
		{Name: "user-x", URL: "https://x.example.com"},
	}
	if _, err := SyncRepositories(s, pre); err != nil {
		t.Fatalf("pre-seed: %v", err)
	}

	// 빈 list → 모든 anycloud-managed 제거 + user 보존.
	added, removed, err := SyncRepositoriesWithCleanup(s, nil)
	if err != nil {
		t.Fatalf("cleanup empty: %v", err)
	}
	if added != 0 {
		t.Errorf("expected 0 added, got %d", added)
	}
	if removed != 2 {
		t.Errorf("expected 2 removed (anycloud-a + anycloud-b), got %d", removed)
	}
	rf, err := repo.LoadFile(s.RepositoryConfig)
	if err != nil {
		t.Fatalf("load: %v", err)
	}
	if len(rf.Repositories) != 1 || rf.Repositories[0].Name != "user-x" {
		t.Errorf("expected only user-x preserved, got %+v",
			func() []string {
				out := []string{}
				for _, e := range rf.Repositories {
					out = append(out, e.Name)
				}
				return out
			}())
	}
}

func TestSyncRepositoriesWithCleanup_UpdateExisting(t *testing.T) {
	s := newTestSettings(t)
	pre := []RepoEntry{
		{Name: "anycloud-prom", URL: "https://old.prom.io"},
	}
	if _, err := SyncRepositories(s, pre); err != nil {
		t.Fatalf("pre-seed: %v", err)
	}

	// Same name, new URL — overwrite + no orphan.
	desired := []RepoEntry{
		{Name: "anycloud-prom", URL: "https://new.prom.io"},
	}
	added, removed, err := SyncRepositoriesWithCleanup(s, desired)
	if err != nil {
		t.Fatalf("update sync: %v", err)
	}
	if added != 1 {
		t.Errorf("expected 1 added, got %d", added)
	}
	if removed != 0 {
		t.Errorf("expected 0 removed (same name kept then updated), got %d", removed)
	}
	rf, err := repo.LoadFile(s.RepositoryConfig)
	if err != nil {
		t.Fatalf("load: %v", err)
	}
	if len(rf.Repositories) != 1 {
		t.Fatalf("expected 1 entry after overwrite, got %d", len(rf.Repositories))
	}
	if rf.Repositories[0].URL != "https://new.prom.io" {
		t.Errorf("URL not updated: got %q", rf.Repositories[0].URL)
	}
}
