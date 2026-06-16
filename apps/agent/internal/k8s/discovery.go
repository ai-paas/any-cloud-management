// Package k8s — discovery / catalog 헬퍼.
//
// Cluster 가 지원하는 모든 API resource (kind) 를 enumerate. UI 의 "resource kind picker"
// 채우는 LIST_RESOURCE_KINDS 명령 백엔드. CRD 도 자연스럽게 포함됨 — discovery API 가 동일하게
// 보고하므로 client 측 추가 필터 불요.
//
// 흐름:
//
//	┌──────────────┐    Discovery.ServerPreferredResources()    ┌─────────────────────────┐
//	│  realClient  │──────────────────────────────────────────► │  K8s discovery API      │
//	└──────────────┘                                            │  (/api, /apis, /apis/*) │
//	       ▲                                                    └─────────────────────────┘
//	       │                ┌───────────────────────────────────────┐
//	       │  []*APIResourceList ◄── normalizeAPIResources(filter+sort) ──── raw groups/versions
//	       │                └───────────────────────────────────────┘
//	       ▼
//	[]APIResourceInfo (UI-facing shape: plural/singular/kind/group/version/namespaced/shortNames)
//
// 필터:
//   - subresource (name 에 "/" — 예: "pods/log", "pods/exec") 제외 — list 안 됨.
//   - verbs 에 "list" 없는 자원 제외 — UI 의 picker 의미 없음.
//
// 정렬: (Group, Plural) 안정 정렬 — backend cache key / UI 표시 결정성.

package k8s

import (
	"context"
	"fmt"
	"sort"
	"strings"

	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
)

// APIResourceInfo — discovery API 결과의 단일 resource. UI 의 kind picker 용으로 정규화된 view.
type APIResourceInfo struct {
	Plural     string   // "pods", "deployments", "storageclasses"
	Singular   string   // "pod"
	Kind       string   // "Pod"
	Group      string   // "", "apps", "storage.k8s.io"
	Version    string   // "v1"
	Namespaced bool
	ShortNames []string // ["po"] for pods, [] when absent
}

// ListAPIResources — server discovery 결과를 list. kubeconfig RBAC 가 GET /api, /apis 권한을
// 가져야 함 (default cluster-admin OK). 결과는 (Group, Plural) 안정 정렬.
//
// Subresource ("pods/log" 등) 와 list verb 미지원 자원은 자동 제외 — picker UI 가 enumerate
// 못하는 entry 는 노출 안 함.
func (c *realClient) ListAPIResources(ctx context.Context) ([]APIResourceInfo, error) {
	// ServerPreferredResources — 각 group 의 preferred version 만 반환. deprecated group 처리.
	// 부분 실패 (일부 group 의 discovery 가 깨진 경우) 도 본 호출은 결과를 partial 로 돌려주는 경우
	// 가 있어 nil 체크가 아닌 error 가 아닌 한 진행.
	lists, err := c.cs.Discovery().ServerPreferredResources()
	if err != nil {
		// client-go 는 partial discovery (일부 group 만 실패) 도 error 로 보고하지만 lists 는
		// 부분 채워서 돌려준다. 운영 cluster 의 CRD 가 깨졌을 때 전체 fail 시키지 않도록
		// lists 가 non-nil 이면 best-effort 로 진행.
		if lists == nil {
			return nil, fmt.Errorf("discovery: %w", err)
		}
	}
	return normalizeAPIResources(lists), nil
}

// normalizeAPIResources — pure helper. raw discovery 결과를 UI 친화 shape 로 변환.
//
// 입력: ServerPreferredResources() 가 반환하는 []*APIResourceList. 각 list 의 GroupVersion 은
// "v1" (core) 또는 "apps/v1" 처럼 group/version 형식.
// 출력: 정규화 + 필터링 + 정렬된 APIResourceInfo slice.
//
// 분리 이유: discovery client 의존 없이 pure data 로 단위 테스트 가능 (k8s.io/client-go 의 fake
// discovery 가 ServerPreferredResources 를 nil 로 반환하기 때문).
func normalizeAPIResources(lists []*metav1.APIResourceList) []APIResourceInfo {
	out := make([]APIResourceInfo, 0, 64)
	for _, list := range lists {
		if list == nil {
			continue
		}
		group, version := splitGroupVersion(list.GroupVersion)
		for _, r := range list.APIResources {
			// Subresource 제외 ("pods/log", "pods/exec" 등 — list 안 됨).
			if strings.Contains(r.Name, "/") {
				continue
			}
			// list verb 없으면 picker 에서 의미 없음.
			if !hasVerb(r.Verbs, "list") {
				continue
			}
			out = append(out, APIResourceInfo{
				Plural:     r.Name,
				Singular:   r.SingularName,
				Kind:       r.Kind,
				Group:      group,
				Version:    version,
				Namespaced: r.Namespaced,
				ShortNames: append([]string(nil), r.ShortNames...), // defensive copy.
			})
		}
	}
	sort.Slice(out, func(i, j int) bool {
		if out[i].Group != out[j].Group {
			return out[i].Group < out[j].Group
		}
		return out[i].Plural < out[j].Plural
	})
	return out
}

// splitGroupVersion — "apps/v1" → ("apps", "v1"). "v1" (core) → ("", "v1").
func splitGroupVersion(gv string) (group, version string) {
	if idx := strings.Index(gv, "/"); idx >= 0 {
		return gv[:idx], gv[idx+1:]
	}
	return "", gv
}

// hasVerb — verbs slice 안에 v 가 있는지 (e.g. "list", "get", "watch").
func hasVerb(verbs metav1.Verbs, v string) bool {
	for _, x := range verbs {
		if x == v {
			return true
		}
	}
	return false
}
