package model

import (
	"regexp"
	"strings"
)

// L7: 모든 provider 공통의 resource 이름 sanitize.
//
// CSP 별 명명 규칙은 미묘하게 다르지만 공통 분모를 따르면 안전하다:
//   - 소문자 + 숫자 + hyphen 만
//   - 첫 글자는 영문자
//   - 끝 글자는 영숫자 (hyphen 으로 끝나지 않게)
//   - 길이 63 초과 금지 (k8s label / DNS-1123 한도)
//
// 입력이 RFC 위반이면 변환해서 안전한 값을 반환한다 (panic 하지 않음 — Pulumi 가 깊은
// 호출에서 cryptic 에러를 내는 것보다 sanitize 가 안전).
func SanitizeResourceName(raw string) string {
	if raw == "" {
		return "x"
	}
	lower := strings.ToLower(raw)
	// 영숫자/hyphen 외 모두 hyphen 으로 변환.
	re := regexp.MustCompile(`[^a-z0-9-]+`)
	cleaned := re.ReplaceAllString(lower, "-")
	// 연속 hyphen 압축.
	cleaned = regexp.MustCompile(`-+`).ReplaceAllString(cleaned, "-")
	// 첫 글자가 숫자/hyphen 이면 'x' prefix.
	if cleaned == "" || !isLetter(cleaned[0]) {
		cleaned = "x" + cleaned
	}
	// 끝 hyphen 제거.
	cleaned = strings.TrimRight(cleaned, "-")
	if len(cleaned) > 63 {
		cleaned = strings.TrimRight(cleaned[:63], "-")
	}
	if cleaned == "" {
		return "x"
	}
	return cleaned
}

// JoinResourceName — `<cluster>-<suffix>` 패턴 안전 결합. 두 입력 모두 sanitize.
func JoinResourceName(parts ...string) string {
	cleaned := make([]string, 0, len(parts))
	for _, p := range parts {
		s := SanitizeResourceName(p)
		if s != "" && s != "x" {
			cleaned = append(cleaned, s)
		}
	}
	if len(cleaned) == 0 {
		return "x"
	}
	joined := strings.Join(cleaned, "-")
	return SanitizeResourceName(joined)
}

func isLetter(b byte) bool {
	return b >= 'a' && b <= 'z'
}
