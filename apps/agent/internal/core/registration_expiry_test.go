package core

import (
	"encoding/base64"
	"encoding/json"
	"testing"
	"time"
)

// 만료된 등록 토큰은 Secret 에 잔존. 파드가 동일 값으로 CrashLoop 을 반복하고, 원인은 서버
// 거부 메시지 확인 후에야 파악 가능. 기동 시점에 선행 검출.

func tokenWithExp(exp int64) string {
	payload, _ := json.Marshal(map[string]int64{"exp": exp})
	return "header." + base64.RawURLEncoding.EncodeToString(payload) + ".sig"
}

func TestExpiredTokenIsDetected(t *testing.T) {
	now := time.Unix(1_700_000_000, 0)

	if !RegistrationTokenExpired(tokenWithExp(now.Unix()-60), now) {
		t.Fatal("만료된 토큰인데 지나갔다")
	}
}

func TestValidTokenIsNotFlagged(t *testing.T) {
	now := time.Unix(1_700_000_000, 0)

	if RegistrationTokenExpired(tokenWithExp(now.Unix()+600), now) {
		t.Fatal("유효한 토큰을 만료라 불렀다")
	}
}

func TestUnreadableTokenIsNotCalledExpired(t *testing.T) {
	// 유효 토큰을 만료로 판정하면 진단 혼선. 불확실한 경우 단정 금지.
	now := time.Now()
	for _, token := range []string{"", "not-a-jwt", "a.b", "a.!!!.c", "a." + base64.RawURLEncoding.EncodeToString([]byte("{}")) + ".c"} {
		if RegistrationTokenExpired(token, now) {
			t.Fatalf("읽을 수 없는 토큰을 만료로 단정했다: %q", token)
		}
	}
}
