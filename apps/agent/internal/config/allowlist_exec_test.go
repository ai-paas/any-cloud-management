// allowed_exec_namespaces 화이트리스트 회귀 테스트.
package config

import (
	"context"
	"testing"

	corev1 "k8s.io/api/core/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/client-go/kubernetes/fake"
)

func TestLoadOnce_ExecNamespaces_Parsed(t *testing.T) {
	cm := &corev1.ConfigMap{
		ObjectMeta: metav1.ObjectMeta{Name: "aipaas-agent-allowlist", Namespace: "aipaas-system"},
		Data: map[string]string{
			"allowed_commands": `- EXEC_POD
`,
			"allowed_exec_namespaces": `- default
- apps
- demo
`,
		},
	}
	cs := fake.NewSimpleClientset(cm)
	loader := NewLoader(cs, "aipaas-system", "aipaas-agent-allowlist")
	if err := loader.LoadOnce(context.Background()); err != nil {
		t.Fatalf("LoadOnce: %v", err)
	}
	policy := loader.Snapshot()

	for _, ns := range []string{"default", "apps", "demo"} {
		if !policy.IsExecNamespaceAllowed(ns) {
			t.Errorf("exec ns %q should be allowed", ns)
		}
	}
	// kube-system 은 화이트리스트 미포함 — denied.
	if policy.IsExecNamespaceAllowed("kube-system") {
		t.Error("kube-system must NOT be exec-allowed by default")
	}
	if !policy.IsCommandAllowed("EXEC_POD") {
		t.Error("EXEC_POD command should be allowed")
	}
}

func TestExecNamespace_NilSafe(t *testing.T) {
	var nilPolicy *AllowList
	if nilPolicy.IsExecNamespaceAllowed("default") {
		t.Error("nil AllowList must deny all exec namespaces")
	}
}

func TestLoadOnce_NoExecNamespacesKey_DefaultsEmpty(t *testing.T) {
	// 기존 운영 ConfigMap 회귀 보호 — exec namespace key 없어도 parse 성공.
	cm := &corev1.ConfigMap{
		ObjectMeta: metav1.ObjectMeta{Name: "aipaas-agent-allowlist", Namespace: "aipaas-system"},
		Data: map[string]string{
			"allowed_commands": `- LIST_PODS
`,
		},
	}
	cs := fake.NewSimpleClientset(cm)
	loader := NewLoader(cs, "aipaas-system", "aipaas-agent-allowlist")
	if err := loader.LoadOnce(context.Background()); err != nil {
		t.Fatalf("LoadOnce: %v", err)
	}
	policy := loader.Snapshot()
	if policy.IsExecNamespaceAllowed("default") {
		t.Error("no allowed_exec_namespaces key → all exec denied (deny-all)")
	}
}
