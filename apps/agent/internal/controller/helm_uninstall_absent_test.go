package controller

import (
	"errors"
	"testing"
)

// 이미 없는 release 를 지우면 backend 의 addon row 가 DELETING 에 갇힌다.
func TestIsReleaseAbsent(t *testing.T) {
	cases := []struct {
		err  error
		want bool
	}{
		{errors.New("uninstall: Release not loaded: gpu-operator: release: not found"), true},
		{errors.New("release: not found"), true},
		{errors.New("context deadline exceeded"), false},
		{nil, false},
	}
	for _, c := range cases {
		if got := isReleaseAbsent(c.err); got != c.want {
			t.Errorf("isReleaseAbsent(%v) = %v, want %v", c.err, got, c.want)
		}
	}
}
