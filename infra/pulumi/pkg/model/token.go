package model

import (
	"crypto/rand"
	"encoding/hex"
	"fmt"
)

// C3: K8s bootstrap token 생성. RFC: [a-z0-9]{6}.[a-z0-9]{16} 형태.
//
// 이전 구현은 모든 cluster 에 동일한 hardcoded "abcdef.0123456789abcdef" 를
// 사용해 cluster 횡 이동 공격 위험이 컸음. 이제 ApplyProviderDefaults 가 호출하면
// 무작위 token 을 생성, spec.JoinToken 이 빈 값일 때만 적용.
//
// 호출자가 명시적으로 spec.JoinToken 을 채워준 경우 (Spring 측이 K8s join 절차에
// 동기화하기 위해) 는 그대로 사용한다. 단 "abcdef.0123..." 같은 알려진 weak
// sentinel 은 명시 입력으로도 거부한다.
func GenerateBootstrapToken() (string, error) {
	idBytes := make([]byte, 3)
	if _, err := rand.Read(idBytes); err != nil {
		return "", fmt.Errorf("bootstrap token id rand: %w", err)
	}
	secretBytes := make([]byte, 8)
	if _, err := rand.Read(secretBytes); err != nil {
		return "", fmt.Errorf("bootstrap token secret rand: %w", err)
	}
	return hex.EncodeToString(idBytes) + "." + hex.EncodeToString(secretBytes), nil
}

// WeakJoinTokens 는 명시 입력으로도 거부할 알려진 sentinel 값들. 운영 진입 전
// 적용 (Pulumi 부팅 시 즉시 fail) 으로 실수 차단.
var WeakJoinTokens = map[string]bool{
	"abcdef.0123456789abcdef": true,
	"00000.0000000000000000":  true,
}

// EnsureJoinToken — defaults 호출 시 빈 token 채움. 비어있지 않은 경우는 그대로 두되
// weak sentinel 이면 새로 생성. caller 가 검증 결과를 받아 즉시 fail 가능하도록 error 반환.
func EnsureJoinToken(current string) (string, error) {
	if current == "" || WeakJoinTokens[current] {
		return GenerateBootstrapToken()
	}
	return current, nil
}
