// Package k8s — fuzzy match helpers for RESOLVE_RESOURCE.
//
// 사용자가 "stragecass" 같은 오타를 입력한 경우 RESTMapper 가 ErrUnsupportedKind 로
// 답한다. 본 파일의 topKByLevenshtein 이 discovery 의 plural 목록과 거리 계산해서
// top-K suggestion 을 만들어 dispatcher 가 응답 result.data.suggestions 로 노출.
//
// 거리 산정:
//   - 고전 edit-distance (insert/delete/substitute = 1) DP. 외부 의존성 없음.
//   - 입력은 lowercased — caller (dispatcher) 가 strings.ToLower 후 전달.
//   - 거리 > 3 의 후보는 모두 제외 — 의미 있는 오타 범위 제한 (UI noise 줄이기).
package k8s

import (
	"sort"
)

// TopKByLevenshtein — input 과 가장 가까운 K 개의 plural 반환. distance > 3 이면 제외.
//
// 결정성: 거리가 동률이면 plural 의 lexicographic 정렬로 tie-break (UI 표시 안정).
// 입력 slice 가 비어있거나 모든 후보가 거리 > 3 이면 빈 slice 반환.
//
// Complexity: O(N * |input| * max(|plural|))  — N 후보 수. discovery 캐시 (~수백) 기준
// 한 호출 당 수 ms 수준.
//
// dispatcher (다른 패키지) 가 RESOLVE_RESOURCE 응답 보강용으로 호출 — exported. 내부
// helper (levenshtein) 는 unexported.
func TopKByLevenshtein(input string, kinds []APIResourceInfo, k int) []string {
	if k <= 0 || len(kinds) == 0 {
		return []string{}
	}
	type scored struct {
		plural string
		dist   int
	}
	const maxDistance = 3
	candidates := make([]scored, 0, len(kinds))
	for _, kind := range kinds {
		// kind.Plural 은 이미 lowercase 라고 가정 (discovery 가 그렇게 반환).
		d := levenshtein(input, kind.Plural)
		if d > maxDistance {
			continue
		}
		candidates = append(candidates, scored{plural: kind.Plural, dist: d})
	}
	sort.Slice(candidates, func(i, j int) bool {
		if candidates[i].dist != candidates[j].dist {
			return candidates[i].dist < candidates[j].dist
		}
		return candidates[i].plural < candidates[j].plural
	})
	if len(candidates) > k {
		candidates = candidates[:k]
	}
	out := make([]string, 0, len(candidates))
	for _, c := range candidates {
		out = append(out, c.plural)
	}
	return out
}

// levenshtein — 표준 edit-distance DP. insert/delete/substitute 비용 모두 1.
// 메모리 최적화: 2-row rolling (full N*M matrix 불요).
func levenshtein(a, b string) int {
	la, lb := len(a), len(b)
	if la == 0 {
		return lb
	}
	if lb == 0 {
		return la
	}
	// prev row + curr row.
	prev := make([]int, lb+1)
	curr := make([]int, lb+1)
	for j := 0; j <= lb; j++ {
		prev[j] = j
	}
	for i := 1; i <= la; i++ {
		curr[0] = i
		for j := 1; j <= lb; j++ {
			cost := 1
			if a[i-1] == b[j-1] {
				cost = 0
			}
			// min(deletion, insertion, substitution)
			del := prev[j] + 1
			ins := curr[j-1] + 1
			sub := prev[j-1] + cost
			curr[j] = minInt(del, minInt(ins, sub))
		}
		prev, curr = curr, prev
	}
	return prev[lb]
}

func minInt(a, b int) int {
	if a < b {
		return a
	}
	return b
}
