// Package k8s — discovery / catalog 헬퍼.
//
// Cluster 가 지원하는 모든 API resource enumerate (UI 의 resource kind picker 백엔드). CRD 도
// 자동 포함 — discovery API 가 동일하게 보고하므로 추가 필터 불요.
//
// 필터:
//   - subresource (name 에 "/", e.g. "pods/log", "pods/exec") 제외 — list 안 됨.
//   - verbs 에 "list" 없는 자원 제외 — UI picker 에 의미 없음.
//
// 정렬: (Group, Plural) 안정 정렬 — backend cache key / UI 결정성 위해.

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

// ListAPIResources: 클러스터의 모든 API 자원 종류를 UI picker 용으로 반환.
// CRD 자동 포함. subresource 와 list verb 미지원 자원 제외, (Group, Plural) 안정 정렬.
func (c *realClient) ListAPIResources(ctx context.Context) ([]APIResourceInfo, error) {
	// 일부 CRD 가 깨져도 정상 자원은 partial 반환 — lists 가 nil 일 때만 에러.
	lists, err := c.cs.Discovery().ServerPreferredResources()
	if err != nil && lists == nil {
		return nil, fmt.Errorf("discovery: %w", err)
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
