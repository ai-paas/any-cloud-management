// dynamic client + RESTMapper 기반 LIST/GET/DELETE 동작 검증.
//
// Test 전략:
//   - meta.NewDefaultRESTMapper 로 in-memory RESTMapper 구성. discovery (실 API 호출) 없이 결정적.
//   - dynamic/fake 의 NewSimpleDynamicClient 로 unstructured.Unstructured 기반 fake K8s 객체 주입.
//   - realClient 의 dyn / mapper 필드를 sync.Once 우회로 직접 set — production path 와 동일한
//     코드를 타지만 외부 의존 없이 검증 가능.
//
// Coverage:
//   - storageclasses (cluster-scoped, group=storage.k8s.io) ListResources
//   - pods (namespaced, core v1) GetResource
//   - customresourcedefinitions (cluster-scoped, group=apiextensions.k8s.io) ResolveResource
//   - short name expansion ("pvc" → "persistentvolumeclaims")
//   - ErrUnsupportedKind sentinel 보존
package k8s

import (
	"context"
	"strings"
	"sync"
	"testing"

	"k8s.io/apimachinery/pkg/api/meta"
	"k8s.io/apimachinery/pkg/apis/meta/v1/unstructured"
	"k8s.io/apimachinery/pkg/runtime"
	"k8s.io/apimachinery/pkg/runtime/schema"
	"k8s.io/client-go/dynamic"
	dynfake "k8s.io/client-go/dynamic/fake"
)

// newTestRESTMapper — dispatcher 가 다루는 자원들로 RESTMapper 구성.
// production 에선 discovery 기반 deferred mapper 가 사용되지만, test 에선 결정적 매핑이 필요.
func newTestRESTMapper() *meta.DefaultRESTMapper {
	gvs := []schema.GroupVersion{
		{Group: "", Version: "v1"},
		{Group: "apps", Version: "v1"},
		{Group: "storage.k8s.io", Version: "v1"},
		{Group: "apiextensions.k8s.io", Version: "v1"},
	}
	m := meta.NewDefaultRESTMapper(gvs)
	// Namespaced.
	m.Add(schema.GroupVersionKind{Group: "", Version: "v1", Kind: "Pod"}, meta.RESTScopeNamespace)
	m.Add(schema.GroupVersionKind{Group: "", Version: "v1", Kind: "ConfigMap"}, meta.RESTScopeNamespace)
	m.Add(schema.GroupVersionKind{Group: "", Version: "v1", Kind: "Secret"}, meta.RESTScopeNamespace)
	m.Add(schema.GroupVersionKind{Group: "", Version: "v1", Kind: "PersistentVolumeClaim"}, meta.RESTScopeNamespace)
	m.Add(schema.GroupVersionKind{Group: "apps", Version: "v1", Kind: "Deployment"}, meta.RESTScopeNamespace)
	// Cluster-scoped.
	m.Add(schema.GroupVersionKind{Group: "", Version: "v1", Kind: "Node"}, meta.RESTScopeRoot)
	m.Add(schema.GroupVersionKind{Group: "", Version: "v1", Kind: "Namespace"}, meta.RESTScopeRoot)
	m.Add(schema.GroupVersionKind{Group: "storage.k8s.io", Version: "v1", Kind: "StorageClass"}, meta.RESTScopeRoot)
	m.Add(schema.GroupVersionKind{Group: "apiextensions.k8s.io", Version: "v1", Kind: "CustomResourceDefinition"}, meta.RESTScopeRoot)
	return m
}

// newTestDynamicScheme — fake dynamic client 가 사용할 scheme + List kind 등록.
//
// Scheme 에 singular GVK 도 등록해야 ObjectTracker.Add(unstructured) 가 정상 동작
// (tracker 가 scheme.ObjectKinds(obj) 로 GVK 를 역추적하기 때문).
func newTestDynamicScheme() (*runtime.Scheme, map[schema.GroupVersionResource]string) {
	scheme := runtime.NewScheme()

	// Register singular + list types as unstructured for each GVK we use.
	registered := []schema.GroupVersionKind{
		{Group: "", Version: "v1", Kind: "Pod"},
		{Group: "", Version: "v1", Kind: "ConfigMap"},
		{Group: "", Version: "v1", Kind: "Node"},
		{Group: "storage.k8s.io", Version: "v1", Kind: "StorageClass"},
		{Group: "apiextensions.k8s.io", Version: "v1", Kind: "CustomResourceDefinition"},
	}
	for _, gvk := range registered {
		scheme.AddKnownTypeWithName(gvk, &unstructured.Unstructured{})
		scheme.AddKnownTypeWithName(gvk.GroupVersion().WithKind(gvk.Kind+"List"), &unstructured.UnstructuredList{})
	}

	gvrToListKind := map[schema.GroupVersionResource]string{
		{Group: "", Version: "v1", Resource: "pods"}:                                          "PodList",
		{Group: "", Version: "v1", Resource: "configmaps"}:                                    "ConfigMapList",
		{Group: "", Version: "v1", Resource: "nodes"}:                                         "NodeList",
		{Group: "storage.k8s.io", Version: "v1", Resource: "storageclasses"}:                  "StorageClassList",
		{Group: "apiextensions.k8s.io", Version: "v1", Resource: "customresourcedefinitions"}: "CustomResourceDefinitionList",
	}
	return scheme, gvrToListKind
}

// newPreparedClient — realClient 의 dyn / mapper 를 미리 채워서 ensureDynamic 우회.
// production 의 sync.Once 와 동일한 lifecycle 을 따르지만 init 비용 없이 즉시 사용 가능.
func newPreparedClient(t *testing.T, objects ...runtime.Object) *realClient {
	t.Helper()
	scheme, gvrToListKind := newTestDynamicScheme()
	dynClient := dynfake.NewSimpleDynamicClientWithCustomListKinds(scheme, gvrToListKind, objects...)
	mapper := newTestRESTMapper()
	c := &realClient{
		dyn:    dynClient,
		mapper: mapper,
	}
	// sync.Once 가 발동되지 않게 미리 Do 처리.
	c.dynOnce.Do(func() {})
	return c
}

// uobj — unstructured.Unstructured helper.
func uobj(apiVersion, kind, name, namespace string) *unstructured.Unstructured {
	u := &unstructured.Unstructured{}
	u.SetAPIVersion(apiVersion)
	u.SetKind(kind)
	u.SetName(name)
	if namespace != "" {
		u.SetNamespace(namespace)
	}
	return u
}

// TestResolveResource_ClusterScopedCRD — storageclasses / customresourcedefinitions 가
// plural 로 정확히 정규화되고 namespaced=false 로 표시되는지.
func TestResolveResource_ClusterScopedCRD(t *testing.T) {
	c := newPreparedClient(t)

	cases := []struct {
		input     string
		wantPlural string
		wantGroup  string
		wantNs     bool
	}{
		{"storageclasses", "storageclasses", "storage.k8s.io", false},
		{"storageclass", "storageclasses", "storage.k8s.io", false},
		{"sc", "storageclasses", "storage.k8s.io", false},
		{"customresourcedefinitions", "customresourcedefinitions", "apiextensions.k8s.io", false},
		{"crd", "customresourcedefinitions", "apiextensions.k8s.io", false},
		{"pods", "pods", "", true},
		{"pod", "pods", "", true},
		{"po", "pods", "", true},
		{"pvc", "persistentvolumeclaims", "", true},
	}
	for _, tc := range cases {
		got, err := c.ResolveResource(tc.input)
		if err != nil {
			t.Errorf("ResolveResource(%q): %v", tc.input, err)
			continue
		}
		if got.Plural != tc.wantPlural || got.Group != tc.wantGroup || got.Namespaced != tc.wantNs {
			t.Errorf("ResolveResource(%q) = %+v, want plural=%q group=%q namespaced=%v",
				tc.input, got, tc.wantPlural, tc.wantGroup, tc.wantNs)
		}
	}
}

// TestResolveResource_UnknownKind — RESTMapper 가 매핑 못 찾으면 ErrUnsupportedKind sentinel.
func TestResolveResource_UnknownKind(t *testing.T) {
	c := newPreparedClient(t)
	_, err := c.ResolveResource("unicornresource")
	if err == nil {
		t.Fatal("expected error for unknown kind")
	}
	if !strings.Contains(err.Error(), ErrUnsupportedKind.Error()) {
		t.Errorf("error should wrap ErrUnsupportedKind: %v", err)
	}
}

// TestListResources_StorageClasses — 하드코딩되지 않은 CRD-ish cluster-scoped
// 자원도 정상 list 되는지.
func TestListResources_StorageClasses(t *testing.T) {
	scObj := uobj("storage.k8s.io/v1", "StorageClass", "standard-rwo", "")
	c := newPreparedClient(t, scObj)

	res, err := c.ListResources(context.Background(), ListResourcesOptions{
		Kind: "storageclasses",
	})
	if err != nil {
		t.Fatalf("ListResources: %v", err)
	}
	if res.ReturnedCount != 1 {
		t.Errorf("returned_count = %d, want 1", res.ReturnedCount)
	}
	if !strings.Contains(res.Items, "standard-rwo") {
		t.Errorf("Items JSON should contain object name: %s", res.Items)
	}
	if !strings.Contains(res.Items, "StorageClass") {
		t.Errorf("Items JSON should contain kind: %s", res.Items)
	}
}

// TestListResources_PodsNamespaced — namespaced 자원의 namespace 필터링.
func TestListResources_PodsNamespaced(t *testing.T) {
	c := newPreparedClient(t,
		uobj("v1", "Pod", "nginx-web", "web"),
		uobj("v1", "Pod", "nginx-api", "api"),
	)
	res, err := c.ListResources(context.Background(), ListResourcesOptions{
		Kind: "pods", Namespace: "web",
	})
	if err != nil {
		t.Fatalf("ListResources: %v", err)
	}
	if res.ReturnedCount != 1 {
		t.Errorf("namespaced filter failed: returned_count = %d, want 1", res.ReturnedCount)
	}
	if !strings.Contains(res.Items, "nginx-web") {
		t.Errorf("Items should include nginx-web: %s", res.Items)
	}
}

// TestListResources_UnsupportedKind — 알 수 없는 kind 는 ErrUnsupportedKind sentinel 반환.
// 본 sentinel 은 dispatcher 가 INVALID_PARAMS/UNSUPPORTED_KIND 응답으로 매핑.
func TestListResources_UnsupportedKind(t *testing.T) {
	c := newPreparedClient(t)
	_, err := c.ListResources(context.Background(), ListResourcesOptions{Kind: "unicorn"})
	if err == nil {
		t.Fatal("expected ErrUnsupportedKind")
	}
	if !strings.Contains(err.Error(), ErrUnsupportedKind.Error()) {
		t.Errorf("err should wrap ErrUnsupportedKind: %v", err)
	}
}

// TestGetResource_Pod — namespaced get → JSON 응답.
func TestGetResource_Pod(t *testing.T) {
	c := newPreparedClient(t, uobj("v1", "Pod", "nginx", "web"))
	got, err := c.GetResource(context.Background(), GetResourceOptions{
		Kind: "pod", Namespace: "web", Name: "nginx",
	})
	if err != nil {
		t.Fatalf("GetResource: %v", err)
	}
	if !strings.Contains(got, `"name":"nginx"`) {
		t.Errorf("response should contain name=nginx: %s", got)
	}
	if !strings.Contains(got, `"kind":"Pod"`) {
		t.Errorf("response should contain kind=Pod: %s", got)
	}
}

// TestDeleteResource_StorageClass — cluster-scoped CRD-ish 자원 삭제. namespace 무시.
func TestDeleteResource_StorageClass(t *testing.T) {
	scObj := uobj("storage.k8s.io/v1", "StorageClass", "gp3", "")
	c := newPreparedClient(t, scObj)

	if err := c.DeleteResource(context.Background(), DeleteResourceOptions{
		Kind: "sc", Name: "gp3",
	}); err != nil {
		t.Fatalf("DeleteResource: %v", err)
	}
	// 삭제 확인 — get 으로 NotFound 검증.
	_, err := c.GetResource(context.Background(), GetResourceOptions{
		Kind: "storageclasses", Name: "gp3",
	})
	if err == nil {
		t.Error("StorageClass should be deleted")
	}
}

// TestDeleteResource_RequiresName — 명시적 가드 회귀 방지.
func TestDeleteResource_RequiresName(t *testing.T) {
	c := newPreparedClient(t)
	err := c.DeleteResource(context.Background(), DeleteResourceOptions{Kind: "pods"})
	if err == nil {
		t.Error("expected error when name is empty")
	}
}

// TestEnsureDynamicConcurrency — sync.Once 의 race-safety 회귀. ResolveResource 를 동시에
// 여러 고루틴이 호출해도 dyn/mapper 가 한 번만 초기화되어야 한다.
//
// concurrency 다이어그램:
//
//	goroutine 1 ┐
//	goroutine 2 ┼─► ResolveResource("pods") ─► ensureDynamic (sync.Once)
//	goroutine 3 ┘                              └─► dyn / mapper 단일 초기화
//
// preparedClient 는 이미 dynOnce.Do(func(){}) 로 무력화돼 있으나, 동시 ResolveResource 호출이
// 결정적으로 같은 결과를 반환하는지만 검증 (실 init path 는 production 에서 검증).
func TestEnsureDynamicConcurrency(t *testing.T) {
	c := newPreparedClient(t)
	const N = 16
	var wg sync.WaitGroup
	wg.Add(N)
	results := make([]string, N)
	for i := 0; i < N; i++ {
		go func(i int) {
			defer wg.Done()
			r, err := c.ResolveResource("pods")
			if err != nil {
				results[i] = "err:" + err.Error()
				return
			}
			results[i] = r.Plural
		}(i)
	}
	wg.Wait()
	for _, r := range results {
		if r != "pods" {
			t.Errorf("concurrent resolve mismatch: %q", r)
		}
	}
}

// 컴파일 보증 — dynamic.Interface 가 우리가 가정하는 타입과 일치하는지.
var _ dynamic.Interface = (*dynfake.FakeDynamicClient)(nil)
