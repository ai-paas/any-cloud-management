package model

import (
	"regexp"
	"strings"
	"testing"
)

// L7 회귀 방지. AWS / Azure / GCP 의 공통 명명 규칙 (영숫자 + hyphen, 첫 글자 영문,
// 끝 hyphen 금지, 63자 cap) 을 모두 만족하는지 확인.
var validNamePattern = regexp.MustCompile(`^[a-z][a-z0-9-]{0,61}[a-z0-9]$|^[a-z]$`)

func TestSanitizeResourceName(t *testing.T) {
	cases := []struct {
		in   string
		want string
	}{
		{"demo-aws-01", "demo-aws-01"},
		{"DEMO_AWS_01", "demo-aws-01"},
		{"my_cluster@test", "my-cluster-test"},
		{"1cluster", "x1cluster"},
		{"--leading-hyphen", "x-leading-hyphen"},
		{"trailing-", "trailing"},
		{"trailing--", "trailing"},
		{"with...dots", "with-dots"},
		{"", "x"},
		{strings.Repeat("a", 100), strings.Repeat("a", 63)},
	}
	for _, c := range cases {
		got := SanitizeResourceName(c.in)
		if got != c.want {
			t.Errorf("SanitizeResourceName(%q) = %q, want %q", c.in, got, c.want)
		}
		if !validNamePattern.MatchString(got) {
			t.Errorf("SanitizeResourceName(%q) = %q which violates RFC-1123-ish pattern", c.in, got)
		}
	}
}

func TestJoinResourceName(t *testing.T) {
	if got := JoinResourceName("demo", "vpc"); got != "demo-vpc" {
		t.Errorf("got %q, want demo-vpc", got)
	}
	if got := JoinResourceName("My_Cluster", "subnet"); got != "my-cluster-subnet" {
		t.Errorf("got %q, want my-cluster-subnet", got)
	}
	// 너무 긴 입력은 truncate 후에도 valid 한지.
	long := JoinResourceName(strings.Repeat("a", 50), strings.Repeat("b", 50))
	if len(long) > 63 {
		t.Errorf("JoinResourceName length %d > 63: %q", len(long), long)
	}
}

func TestGenerateBootstrapToken(t *testing.T) {
	// 형식: [a-z0-9]{6}.[a-z0-9]{16}
	tokenPattern := regexp.MustCompile(`^[a-z0-9]{6}\.[a-z0-9]{16}$`)
	for i := 0; i < 50; i++ {
		tok, err := GenerateBootstrapToken()
		if err != nil {
			t.Fatalf("GenerateBootstrapToken: %v", err)
		}
		if !tokenPattern.MatchString(tok) {
			t.Errorf("token %q violates pattern", tok)
		}
	}
}

func TestEnsureJoinToken_RejectsWeakSentinel(t *testing.T) {
	// 빈 입력 → 새로 생성.
	tok, err := EnsureJoinToken("")
	if err != nil || tok == "" {
		t.Fatalf("empty input: %v, tok=%q", err, tok)
	}

	// 알려진 weak sentinel 도 거부 → 새로 생성.
	tok2, err := EnsureJoinToken("abcdef.0123456789abcdef")
	if err != nil || tok2 == "abcdef.0123456789abcdef" {
		t.Fatalf("weak sentinel passed through: tok=%q, err=%v", tok2, err)
	}

	// 정상 token 은 그대로 통과.
	user := "abcdef.1234567890abcdef"
	tok3, err := EnsureJoinToken(user)
	if err != nil || tok3 != user {
		t.Fatalf("user-supplied token rejected: tok=%q, err=%v", tok3, err)
	}
}

func TestGenerateBootstrapToken_Uniqueness(t *testing.T) {
	seen := make(map[string]bool, 100)
	for i := 0; i < 100; i++ {
		tok, err := GenerateBootstrapToken()
		if err != nil {
			t.Fatalf("generate: %v", err)
		}
		if seen[tok] {
			t.Fatalf("duplicate token generated: %s", tok)
		}
		seen[tok] = true
	}
}
