// LIST_RESOURCE_KINDS — discovery API enumerate. UI 의 resource kind picker backend.
//
// Test 전략:
//   - normalizeAPIResources 는 pure helper — fake discovery 의존 없이 단위 테스트.
//   - 정렬 (Group, Plural) / subresource 필터 ("pods/log") / list-verb 미지원 필터 / group-version
//     split ("apps/v1" vs "v1") / shortNames defensive copy 등 회귀 검증.
//   - ListAPIResources 는 fake clientset 의 FakeDiscovery 가 ServerPreferredResources 를
//     nil 로 반환하므로 별도 integration smoke 만. (실 cluster 검증은 e2e 책임.)
//
// 다이어그램:
//
//	[]APIResourceList ──► normalizeAPIResources ──► []APIResourceInfo
//	(raw discovery)         │  filter sub("/")        (UI shape, sorted by Group/Plural)
//	                        │  filter !list verb
//	                        │  split GroupVersion
//	                        │  defensive-copy ShortNames
//	                        ▼
package k8s

import (
	"reflect"
	"testing"

	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
)

// makeResource — test helper. ShortNames 는 nil 가능.
func makeResource(name, singular, kind string, namespaced bool, verbs metav1.Verbs, short []string) metav1.APIResource {
	return metav1.APIResource{
		Name:         name,
		SingularName: singular,
		Namespaced:   namespaced,
		Kind:         kind,
		Verbs:        verbs,
		ShortNames:   short,
	}
}

// TestNormalizeAPIResources_FilterAndSort — subresource / list-verb 필터링 + (Group, Plural) 정렬
// + group-version split (core "v1" → group="", "apps/v1" → group="apps") 회귀.
func TestNormalizeAPIResources_FilterAndSort(t *testing.T) {
	lists := []*metav1.APIResourceList{
		{
			GroupVersion: "apps/v1",
			APIResources: []metav1.APIResource{
				makeResource("deployments", "deployment", "Deployment", true,
					metav1.Verbs{"get", "list", "watch", "create", "update", "patch", "delete"},
					[]string{"deploy"}),
				makeResource("deployments/scale", "", "Scale", true,
					metav1.Verbs{"get", "update", "patch"}, nil), // subresource — must skip.
				makeResource("deployments/status", "", "Deployment", true,
					metav1.Verbs{"get", "update", "patch"}, nil), // subresource — must skip.
			},
		},
		{
			GroupVersion: "v1",
			APIResources: []metav1.APIResource{
				makeResource("pods", "pod", "Pod", true,
					metav1.Verbs{"get", "list", "watch", "create", "update", "patch", "delete"},
					[]string{"po"}),
				makeResource("pods/log", "", "Pod", true, metav1.Verbs{"get"}, nil),  // subresource.
				makeResource("pods/exec", "", "Pod", true, metav1.Verbs{"create"}, nil), // subresource.
				makeResource("services", "service", "Service", true,
					metav1.Verbs{"get", "list", "watch"}, []string{"svc"}),
				// componentstatuses 는 list 가능하지만 historical로 verbs 가 list 빠진 경우도 있음.
				// 본 케이스는 verbs 가 list 없으면 skip 되는지 회귀.
				makeResource("bindings", "binding", "Binding", true,
					metav1.Verbs{"create"}, nil), // no list verb — must skip.
			},
		},
		{
			GroupVersion: "storage.k8s.io/v1",
			APIResources: []metav1.APIResource{
				makeResource("storageclasses", "storageclass", "StorageClass", false,
					metav1.Verbs{"get", "list", "watch"}, []string{"sc"}),
			},
		},
		nil, // defensive — nil list 도 skip.
	}

	got := normalizeAPIResources(lists)

	want := []APIResourceInfo{
		// Group="" 가 먼저, 그 안에서 plural 알파벳 정렬.
		{Plural: "pods", Singular: "pod", Kind: "Pod", Group: "", Version: "v1",
			Namespaced: true, ShortNames: []string{"po"}},
		{Plural: "services", Singular: "service", Kind: "Service", Group: "", Version: "v1",
			Namespaced: true, ShortNames: []string{"svc"}},
		// Group="apps" 다음.
		{Plural: "deployments", Singular: "deployment", Kind: "Deployment", Group: "apps", Version: "v1",
			Namespaced: true, ShortNames: []string{"deploy"}},
		// Group="storage.k8s.io" 마지막.
		{Plural: "storageclasses", Singular: "storageclass", Kind: "StorageClass",
			Group: "storage.k8s.io", Version: "v1", Namespaced: false, ShortNames: []string{"sc"}},
	}

	if !reflect.DeepEqual(got, want) {
		t.Errorf("normalizeAPIResources mismatch:\n  got:  %#v\n  want: %#v", got, want)
	}
}

// TestNormalizeAPIResources_ShortNamesDefensiveCopy — 원본 ShortNames slice 가 mutate 돼도
// 결과 entry 의 ShortNames 는 영향 없어야 함 (alias 회귀 방지).
func TestNormalizeAPIResources_ShortNamesDefensiveCopy(t *testing.T) {
	src := []string{"po"}
	lists := []*metav1.APIResourceList{
		{
			GroupVersion: "v1",
			APIResources: []metav1.APIResource{
				makeResource("pods", "pod", "Pod", true, metav1.Verbs{"list"}, src),
			},
		},
	}
	got := normalizeAPIResources(lists)
	if len(got) != 1 {
		t.Fatalf("expected 1 entry, got %d", len(got))
	}
	// 원본 mutate.
	src[0] = "MUTATED"
	if got[0].ShortNames[0] != "po" {
		t.Errorf("ShortNames was aliased — got[0]=%q after mutation, want %q", got[0].ShortNames[0], "po")
	}
}

// TestNormalizeAPIResources_EmptyShortNames — ShortNames 가 nil 인 자원도 안전하게 처리
// (UI 의 picker 가 빈 슬라이스 / nil 차이로 깨지지 않게 빈 slice 보장).
func TestNormalizeAPIResources_EmptyShortNames(t *testing.T) {
	lists := []*metav1.APIResourceList{
		{
			GroupVersion: "v1",
			APIResources: []metav1.APIResource{
				makeResource("namespaces", "namespace", "Namespace", false,
					metav1.Verbs{"get", "list"}, nil),
			},
		},
	}
	got := normalizeAPIResources(lists)
	if len(got) != 1 {
		t.Fatalf("expected 1 entry, got %d", len(got))
	}
	// nil ShortNames 는 append([]string(nil), nil...) → nil. 양쪽 다 len 0 인지만 검증.
	if len(got[0].ShortNames) != 0 {
		t.Errorf("ShortNames should be empty, got %v", got[0].ShortNames)
	}
}

// TestSplitGroupVersion — group-version split helper 경계 조건.
func TestSplitGroupVersion(t *testing.T) {
	cases := []struct {
		in          string
		wantGroup   string
		wantVersion string
	}{
		{"v1", "", "v1"},                                 // core.
		{"apps/v1", "apps", "v1"},                         // typed group.
		{"storage.k8s.io/v1", "storage.k8s.io", "v1"},     // dotted group.
		{"apiextensions.k8s.io/v1", "apiextensions.k8s.io", "v1"}, // CRD group.
		{"custom.example.com/v1alpha1", "custom.example.com", "v1alpha1"}, // CRD alpha.
	}
	for _, c := range cases {
		g, v := splitGroupVersion(c.in)
		if g != c.wantGroup || v != c.wantVersion {
			t.Errorf("splitGroupVersion(%q) = (%q, %q), want (%q, %q)",
				c.in, g, v, c.wantGroup, c.wantVersion)
		}
	}
}

// TestHasVerb — verb 매칭. case-sensitive (K8s discovery 는 모두 lowercase verb 사용).
func TestHasVerb(t *testing.T) {
	if !hasVerb(metav1.Verbs{"get", "list", "watch"}, "list") {
		t.Error("expected list verb to match")
	}
	if hasVerb(metav1.Verbs{"get", "watch"}, "list") {
		t.Error("expected no match when list verb absent")
	}
	if hasVerb(nil, "list") {
		t.Error("nil verbs should not match")
	}
	// LIST (uppercase) 는 매칭 안 함 — discovery 는 lowercase 만 발급.
	if hasVerb(metav1.Verbs{"LIST"}, "list") {
		t.Error("case-sensitivity broken")
	}
}
