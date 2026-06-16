package com.aipaas.anycloud.common.logging;

import java.util.List;
import java.util.regex.Pattern;

/**
 * CSP 자격증명 / IAM 식별자가 로그 / 응답 본문에 새는 걸 막는 redactor.
 *
 * <p>마스킹 대상은 {@code docs/operations/credential-failure-policy.md} § 3 의 reconnaissance
 * 위험 정보 목록을 따른다. 패턴 추가 시 정책 문서를 먼저 갱신.
 *
 * <p>설계 원칙:
 * <ul>
 *   <li>False positive 최소화 — 명확하게 식별 가능한 포맷만 마스킹. project-name-12345 같은
 *       일반 식별자는 마스킹하지 않는다 (운영 로그 가독성 우선).</li>
 *   <li>전체 토큰을 가리고 마지막 4 자만 노출 — 운영자가 어느 자격증명인지 식별은 가능하되
 *       payload 재구성은 불가능.</li>
 *   <li>Idempotent — 이미 마스킹된 문자열을 다시 처리해도 변화 없음.</li>
 * </ul>
 */
public final class SensitiveDataRedactor {

    private SensitiveDataRedactor() {}

    private record RedactRule(Pattern pattern, String label) {
        String apply(String input) {
            return pattern.matcher(input).replaceAll(match -> {
                String value = match.group();
                String tail = value.length() <= 4 ? value : value.substring(value.length() - 4);
                return "<" + label + ":****" + tail + ">";
            });
        }
    }

    private static final List<RedactRule> RULES = List.of(
            new RedactRule(Pattern.compile("(?i)\\b(?:AKIA|ASIA)[0-9A-Z]{16}\\b"), "AWS_KEY"),
            new RedactRule(Pattern.compile("\\barn:aws:[^\\s\"']+:\\d{12}:[^\\s\"']+"), "AWS_ARN"),
            new RedactRule(Pattern.compile("(?<![0-9])\\d{12}(?![0-9])"), "AWS_ACCT"),
            new RedactRule(
                    Pattern.compile("(?i)\\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\b"), "UUID"),
            new RedactRule(Pattern.compile("\\b(?:[0-9a-f]{2}:){15}[0-9a-f]{2}\\b"), "OCI_FP"),
            new RedactRule(Pattern.compile("(?i)\\bocid1\\.[a-z]+\\.[a-z0-9._-]+"), "OCID"),
            new RedactRule(
                    Pattern.compile("-----BEGIN[^-]+PRIVATE KEY-----[\\s\\S]+?-----END[^-]+PRIVATE KEY-----"),
                    "PRIVATE_KEY"));

    /**
     * 입력 문자열에서 알려진 sensitive 패턴을 마스킹. null/blank 는 그대로 반환.
     *
     * <p>현재 마스킹: AWS Access Key ID, AWS ARN, AWS Account ID (12-digit), UUID (Azure
     * subscription/tenant), OCI fingerprint, OCI OCID, PEM private key block.
     */
    public static String redact(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        String result = input;
        for (RedactRule rule : RULES) {
            result = rule.apply(result);
        }
        return result;
    }
}
