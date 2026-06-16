// K8s Impersonation context propagation.
//
// Backend 가 CommandRequest 의 impersonate_user / impersonate_groups / impersonate_extras 필드를
// 채워 보내면, dispatcher.go 가 본 패키지의 Impersonation 으로 변환해 context 에 attach.
// realClient 의 각 K8s API 호출 path (List/Get/Apply/Delete/Logs) 가 context 에서 추출해
// rest.Config.Impersonate 설정된 clone client 를 사용.
//
// 빈 user → no-op (admin-equivalent 동작 유지). agent SA 의 ClusterRole 에 impersonate verb
// (users / groups / serviceaccounts) 가 있어야 K8s 가 허용 — 없으면 K8s 가 403 (backend 의
// classifyDegradedCause 가 FORBIDDEN 라벨로 노출).
package k8s

import (
	"context"
)

// Impersonation — K8s rest.ImpersonationConfig 와 동일 구조. user 필수, 나머지 optional.
type Impersonation struct {
	User   string              // K8s username (OIDC sub / gateway-resolved)
	Groups []string            // K8s group memberships
	Extras map[string][]string // X-Remote-Extra-* 등가 (드물게 사용)
}

// IsZero — user 가 비면 impersonation 미지정. dispatcher.go 가 빈 field 받았을 때.
func (i *Impersonation) IsZero() bool {
	return i == nil || i.User == ""
}

// 본 패키지 외부와 충돌하지 않도록 unexported type.
type impersonationCtxKey struct{}

// ContextWithImpersonation — caller 가 impersonation 을 ctx 에 attach. nil/zero 면 ctx 그대로 반환.
func ContextWithImpersonation(ctx context.Context, imp *Impersonation) context.Context {
	if imp.IsZero() {
		return ctx
	}
	return context.WithValue(ctx, impersonationCtxKey{}, imp)
}

// ImpersonationFromContext — k8s client 가 호출 시점에 추출. 없으면 nil — 호출자는
// admin-equivalent 동작 유지.
func ImpersonationFromContext(ctx context.Context) *Impersonation {
	if ctx == nil {
		return nil
	}
	v, _ := ctx.Value(impersonationCtxKey{}).(*Impersonation)
	return v
}
