package k8s

import (
	"reflect"
	"testing"
)

// Levenshtein 자체 — exact / 1-char typo / unrelated. table-driven.
func TestLevenshtein(t *testing.T) {
	cases := []struct {
		a, b string
		want int
	}{
		{"", "", 0},
		{"pods", "pods", 0},
		{"pod", "pods", 1},        // 1 insert
		{"pds", "pods", 1},        // 1 insert
		{"ppds", "pods", 1},       // 1 sub (p→o)
		{"xyz", "pods", 4},        // 3 sub + 1 del
		{"stragecls", "storageclass", 3},        // 운영 typo — within threshold
		{"storageclas", "storageclasses", 3},    // 3 inserts ("ses")
	}
	for _, c := range cases {
		got := levenshtein(c.a, c.b)
		if got != c.want {
			t.Errorf("levenshtein(%q, %q) = %d, want %d", c.a, c.b, got, c.want)
		}
	}
}

func TestTopKByLevenshtein_ExactMatch(t *testing.T) {
	kinds := []APIResourceInfo{
		{Plural: "pods"},
		{Plural: "services"},
		{Plural: "deployments"},
	}
	got := TopKByLevenshtein("pods", kinds, 3)
	if len(got) == 0 || got[0] != "pods" {
		t.Errorf("exact match should rank pods first: got %v", got)
	}
}

func TestTopKByLevenshtein_OneCharTypo(t *testing.T) {
	// "ppds" → 1 sub from "pods" (distance 1) — should be top.
	kinds := []APIResourceInfo{
		{Plural: "pods"},
		{Plural: "services"},
		{Plural: "configmaps"},
	}
	got := TopKByLevenshtein("ppds", kinds, 3)
	if len(got) == 0 || got[0] != "pods" {
		t.Errorf("1-char typo should suggest pods: got %v", got)
	}
}

func TestTopKByLevenshtein_StorageClassesTypo(t *testing.T) {
	// "storageclas" vs "storageclasses" — 거리 3 (3 inserts), threshold (≤3) 매칭 경계.
	kinds := []APIResourceInfo{
		{Plural: "pods"},
		{Plural: "storageclasses"},
		{Plural: "services"},
		{Plural: "configmaps"},
	}
	got := TopKByLevenshtein("storageclas", kinds, 3)
	found := false
	for _, p := range got {
		if p == "storageclasses" {
			found = true
			break
		}
	}
	if !found {
		t.Errorf("storageclas should fuzzy-match storageclasses (≤3): got %v", got)
	}
}

func TestTopKByLevenshtein_UnrelatedExcluded(t *testing.T) {
	// "xyz" — distance to anything sensible > 3. expect empty or no false positives.
	kinds := []APIResourceInfo{
		{Plural: "pods"},
		{Plural: "services"},
		{Plural: "deployments"},
		{Plural: "configmaps"},
		{Plural: "storageclasses"},
	}
	got := TopKByLevenshtein("xyz", kinds, 3)
	// All candidates have distance > 3. Expect empty.
	if len(got) != 0 {
		t.Errorf("xyz should not match any (≤3): got %v", got)
	}
}

func TestTopKByLevenshtein_KCap(t *testing.T) {
	// Many close candidates — should cap at K.
	kinds := []APIResourceInfo{
		{Plural: "pod"},  // 1
		{Plural: "pos"},  // 1
		{Plural: "pds"},  // 1
		{Plural: "ods"},  // 1
	}
	got := TopKByLevenshtein("pods", kinds, 2)
	if len(got) != 2 {
		t.Errorf("K=2 should cap at 2: got %v (len=%d)", got, len(got))
	}
}

func TestTopKByLevenshtein_DeterministicTieBreak(t *testing.T) {
	// All same distance → alphabetical tie-break.
	kinds := []APIResourceInfo{
		{Plural: "bb"},
		{Plural: "aa"},
		{Plural: "cc"},
	}
	got := TopKByLevenshtein("ab", kinds, 3)
	want := []string{"aa", "bb", "cc"}     // all distance 1, alphabetical order.
	if !reflect.DeepEqual(got, want) {
		t.Errorf("tie-break = %v, want %v", got, want)
	}
}

func TestTopKByLevenshtein_EmptyKinds(t *testing.T) {
	got := TopKByLevenshtein("pods", nil, 3)
	if len(got) != 0 {
		t.Errorf("nil kinds → empty: got %v", got)
	}
}

func TestTopKByLevenshtein_ZeroK(t *testing.T) {
	kinds := []APIResourceInfo{{Plural: "pods"}}
	got := TopKByLevenshtein("pods", kinds, 0)
	if len(got) != 0 {
		t.Errorf("K=0 → empty: got %v", got)
	}
}
