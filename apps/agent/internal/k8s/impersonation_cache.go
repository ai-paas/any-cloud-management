// LRU cache for per-user impersonating K8s clients.
//
// clientsetForCtx / dynamicForCtx 가 매 호출마다 rest.CopyConfig + NewForConfig 를 수행하면
// UI burst (사용자가 list 화면을 빠르게 refresh) 시 GC 압박 + transport pool 단편화 (각 client 마다
// 별도 HTTP/2 transport + TLS dial 가능성) 가 발생.
//
// 본 파일이 user identity 별로 typed clientset + dynamic.Interface 를 캐시한 채 유지 — N 명의
// 동시 사용자가 안정적으로 진행해도 각자 1 set 의 client 만 보유. TTL 5분 / max 32 entries.
//
// Thread-safety: hashicorp/golang-lru v2 의 cache 가 자체 mutex 보유. 동시 호출 안전.
package k8s

import (
	"crypto/sha256"
	"encoding/hex"
	"sort"
	"strings"
	"sync"
	"time"

	lru "github.com/hashicorp/golang-lru/v2"
	"k8s.io/client-go/dynamic"
	"k8s.io/client-go/kubernetes"
	"k8s.io/client-go/rest"
)

// impersonatedClients — 한 user identity 의 typed + dynamic client 묶음.
type impersonatedClients struct {
	cs        kubernetes.Interface
	dyn       dynamic.Interface
	createdAt time.Time
}

// impersonationCache — LRU + TTL. max 32 entries (cluster 당 동시 user 32명 cover, 그 이상은 LRU 강등).
type impersonationCache struct {
	lru *lru.Cache[string, *impersonatedClients]
	ttl time.Duration
}

var (
	cacheOnce sync.Once
	cache     *impersonationCache
)

// getImpersonationCache — process-wide singleton. 첫 호출 시 LRU 생성.
func getImpersonationCache() *impersonationCache {
	cacheOnce.Do(func() {
		// 32 entries — cluster N 명 동시 사용 + 한 명이 5분 안에 32번 다른 identity 로 호출하는 일은 없음.
		l, _ := lru.New[string, *impersonatedClients](32)
		cache = &impersonationCache{lru: l, ttl: 5 * time.Minute}
	})
	return cache
}

// key — identity 의 stable hash. groups/extras 도 sort 후 join 해서 순서 의존성 제거.
func impersonationKey(imp *Impersonation) string {
	groupsCopy := append([]string(nil), imp.Groups...)
	sort.Strings(groupsCopy)

	extraKeys := make([]string, 0, len(imp.Extras))
	for k := range imp.Extras {
		extraKeys = append(extraKeys, k)
	}
	sort.Strings(extraKeys)
	extrasJoined := make([]string, 0, len(extraKeys))
	for _, k := range extraKeys {
		vs := append([]string(nil), imp.Extras[k]...)
		sort.Strings(vs)
		extrasJoined = append(extrasJoined, k+"="+strings.Join(vs, ","))
	}

	raw := imp.User + "|" + strings.Join(groupsCopy, ",") + "|" + strings.Join(extrasJoined, ";")
	sum := sha256.Sum256([]byte(raw))
	return hex.EncodeToString(sum[:16]) // 16 byte (32 hex char) — 충돌 확률 무시 가능.
}

// getOrCreate — cache hit 이면 (cs, dyn) 반환, miss 또는 expired 면 build 후 cache.
// build 가 실패하면 cache 에 저장 안 함 (caller 는 매번 retry).
func (c *impersonationCache) getOrCreate(imp *Impersonation, base *rest.Config) (kubernetes.Interface, dynamic.Interface, error) {
	key := impersonationKey(imp)
	if entry, ok := c.lru.Get(key); ok {
		if time.Since(entry.createdAt) < c.ttl {
			return entry.cs, entry.dyn, nil
		}
		// TTL 만료 — remove 후 rebuild.
		c.lru.Remove(key)
	}

	cfg := rest.CopyConfig(base)
	cfg.Impersonate = rest.ImpersonationConfig{
		UserName: imp.User,
		Groups:   imp.Groups,
		Extra:    imp.Extras,
	}
	cs, err := kubernetes.NewForConfig(cfg)
	if err != nil {
		return nil, nil, err
	}
	dyn, err := dynamic.NewForConfig(cfg)
	if err != nil {
		return nil, nil, err
	}
	c.lru.Add(key, &impersonatedClients{cs: cs, dyn: dyn, createdAt: time.Now()})
	return cs, dyn, nil
}
