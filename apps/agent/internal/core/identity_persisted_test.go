// identity 의 Secret 저장 여부를 결과에 포함.
//
// 미저장 시 해당 파드는 메모리 token 으로만 동작. 문제는 다음 재시작 시점에 드러나며, 그때는
// 등록 토큰(10분 TTL)이 만료돼 파드가 CrashLoop 진입. 현 시점에 노출되지 않으면 검출 불가.
package core

import (
	"context"
	"testing"
	"time"
)

func TestIdentityLoadedFromStoreCountsAsPersisted(t *testing.T) {
	store := &InMemoryIdentityStore{}
	if err := store.Save(context.Background(), &IdentityMaterial{
		IdentityToken: "tok",
		ExpiresAt:     time.Now().Add(30 * 24 * time.Hour).UTC().Format(time.RFC3339),
		ClusterId:     "demo-cluster",
	}); err != nil {
		t.Fatal(err)
	}

	result, err := BootstrapIdentity(context.Background(), BootstrapConfig{
		BackendAddr:     "127.0.0.1:1",
		DialTimeout:     time.Second,
		RegisterTimeout: time.Second,
	}, store, 5*time.Minute)
	if err != nil {
		t.Fatal(err)
	}

	if !result.IdentityPersisted {
		t.Fatal("Secret 에서 읽은 identity 인데 저장되지 않은 것으로 보고했다")
	}
}
