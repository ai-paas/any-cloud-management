package providers

import "fmt"

// registry — build tag 로 included 된 CSP 만 init() 에서 등록.
// CSP 추가 시 factory_<csp>.go 한 파일만 새로 만들면 됨 — factory.go 변경 불필요.
//
// New() 는 read-only access — init() 호출 후엔 mutation 없으므로 별도 lock 없이 안전.
var registry = map[string]func() ClusterProvisioner{}

// Register 는 build tag 로 included 된 provider 의 init() 에서만 호출.
// 외부 호출 / runtime 호출 금지 — registry 는 init 종료 후 read-only.
func Register(name string, ctor func() ClusterProvisioner) {
	registry[name] = ctor
}

// New 는 normalize 된 provider name 으로 registry lookup.
// build tag 에 포함되지 않은 CSP 호출 시 rebuild 가이드 포함된 error 반환.
func New(provider string) (ClusterProvisioner, error) {
	normalized, err := NormalizeProvider(provider)
	if err != nil {
		return nil, err
	}
	ctor, ok := registry[normalized]
	if !ok {
		return nil, fmt.Errorf(
			"provider %q not compiled into this binary; rebuild with -tags=%s (or -tags=all)",
			normalized, normalized,
		)
	}
	return ctor(), nil
}
