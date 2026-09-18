package core

import (
	"encoding/base64"
	"encoding/json"
	"testing"
	"time"
)

// 만료된 등록 토큰은 Secret 에 그대로 남는다. 파드는 같은 값으로 CrashLoop 을 돌고,
// 원인은 서버가 거부하는 메시지를 읽어야만 드러난다. 기동 시점에 먼저 짚어 준다.

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
	// 멀쩡한 토큰을 만료라 부르면 진단이 더 꼬인다. 모르면 단정하지 않는다.
	now := time.Now()
	for _, token := range []string{"", "not-a-jwt", "a.b", "a.!!!.c", "a." + base64.RawURLEncoding.EncodeToString([]byte("{}")) + ".c"} {
		if RegistrationTokenExpired(token, now) {
			t.Fatalf("읽을 수 없는 토큰을 만료로 단정했다: %q", token)
		}
	}
}
