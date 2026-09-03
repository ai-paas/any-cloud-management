package io.aipaas.cluster.provisioning.program;

import java.util.regex.Pattern;

/**
 * Cloud-agnostic resource naming. CSP 공통 분모 규칙:
 *
 * <ul>
 *   <li>소문자 + 숫자 + hyphen 만
 *   <li>첫 글자 영문자
 *   <li>끝 글자 영숫자 (hyphen 으로 끝나지 않음)
 *   <li>최대 63자 (k8s label / DNS-1123 한도)
 * </ul>
 *
 * <p>Go {@code infra/pulumi/pkg/model/names.go} 등가물. Pulumi 가 깊은 호출에서 cryptic 에러를 내는 것보다
 * 본 sanitize 가 안전 — panic 대신 변환.
 */
public final class ResourceNames {

    private static final Pattern NON_ALPHANUMERIC = Pattern.compile("[^a-z0-9-]+");
    private static final Pattern MULTI_HYPHEN = Pattern.compile("-+");

    private ResourceNames() {}

    public static String sanitize(String raw) {
        if (raw == null || raw.isEmpty()) return "x";
        String lower = raw.toLowerCase();
        String cleaned = NON_ALPHANUMERIC.matcher(lower).replaceAll("-");
        cleaned = MULTI_HYPHEN.matcher(cleaned).replaceAll("-");
        if (cleaned.isEmpty() || !isLetter(cleaned.charAt(0))) {
            cleaned = "x" + cleaned;
        }
        cleaned = trimRight(cleaned, '-');
        if (cleaned.length() > 63) {
            cleaned = trimRight(cleaned.substring(0, 63), '-');
        }
        return cleaned.isEmpty() ? "x" : cleaned;
    }

    /** {@code <cluster>-<suffix>} 등 sanitize 후 결합. 빈/x 토큰 제외. */
    public static String join(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            String s = sanitize(part);
            if (s.isEmpty() || "x".equals(s)) continue;
            if (sb.length() > 0) sb.append('-');
            sb.append(s);
        }
        if (sb.length() == 0) return "x";
        return sanitize(sb.toString());
    }

    private static boolean isLetter(char c) {
        return c >= 'a' && c <= 'z';
    }

    private static String trimRight(String s, char ch) {
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == ch) end--;
        return s.substring(0, end);
    }
}
