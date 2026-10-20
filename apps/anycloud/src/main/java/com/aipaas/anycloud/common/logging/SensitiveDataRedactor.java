package com.aipaas.anycloud.common.logging;

import java.util.List;
import java.util.regex.Pattern;

/**
 * CSP 자격증명과 IAM 식별자의 로그, 응답 본문 노출 차단.
 *
 * <p>마스킹 대상은 {@code docs/operations/credential-failure-policy.md} § 3 기준. 패턴 추가 시
 * 정책 문서 선행 수정.
 *
 * <p>마지막 4자 보존. 운영자의 자격증명 식별에 필요. 형식이 명확한 값만 마스킹하므로
 * {@code project-name-12345} 같은 일반 식별자는 유지. 마스킹된 문자열 재입력 시 결과 동일.
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
            // service, region 을 콜론까지 포함하는 문자 클래스로 두면 ARN 이 아닌 긴 입력에서
            // 역추적 비용이 제곱 증가 (polynomial ReDoS). 두 구간을 콜론으로 분리해 분기 제거.
            // region 은 IAM ARN 처럼 비어 있을 수 있음.
            new RedactRule(Pattern.compile("\\barn:aws:[^\\s\"':]+:[^\\s\"':]*:\\d{12}:[^\\s\"']+"), "AWS_ARN"),
            new RedactRule(Pattern.compile("(?<![0-9])\\d{12}(?![0-9])"), "AWS_ACCT"),
            new RedactRule(
                    Pattern.compile("(?i)\\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\b"), "UUID"),
            new RedactRule(Pattern.compile("\\b(?:[0-9a-f]{2}:){15}[0-9a-f]{2}\\b"), "OCI_FP"),
            new RedactRule(Pattern.compile("(?i)\\bocid1\\.[a-z]+\\.[a-z0-9._-]+"), "OCID"),
            new RedactRule(
                    // BEGIN 과 PRIVATE KEY 사이는 " RSA ", " OPENSSH " 수준으로 짧음. [^-]+ 로
                    // 열어 두면 END 미발견 긴 입력에서 역추적 비용이 제곱 증가. 본문도 동일
                    // 이유로 상한 설정. RSA 4096 PEM 이 3.2KB 이므로 16KB 면 실제 키 전체 수용.
                    Pattern.compile(
                            "-----BEGIN[A-Z ]{0,32}PRIVATE KEY-----[\\s\\S]{0,16384}?-----END[A-Z ]{0,32}PRIVATE KEY-----"),
                    "PRIVATE_KEY"));

    /**
     * JSON 키 이름 기준 값 마스킹.
     *
     * <p>{@link #RULES} 는 값의 형식만 판정. AWS 시크릿 키처럼 고정 형식이 없는 값은 미차단.
     * 키 이름은 보존해 마스킹 대상 식별 가능.
     */
    private static final Pattern SECRET_FIELD = Pattern.compile(
            "(?i)(\"[^\"]*(?:secret|password|passwd|pwd|token|private_key|privatekey|api_key|apikey)[^\"]*\"\\s*:\\s*)"
                    + "\"(?:[^\"\\\\]|\\\\.)*\"");

    /** 입력 문자열에서 알려진 sensitive 패턴을 마스킹. null/blank 는 그대로 반환. */
    public static String redact(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        String result = SECRET_FIELD.matcher(input).replaceAll("$1\"<REDACTED>\"");
        for (RedactRule rule : RULES) {
            result = rule.apply(result);
        }
        return result;
    }
}
