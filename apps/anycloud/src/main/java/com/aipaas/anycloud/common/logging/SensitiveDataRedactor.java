package com.aipaas.anycloud.common.logging;

import java.util.List;
import java.util.regex.Pattern;

/**
 * CSP 자격증명과 IAM 식별자가 로그나 응답 본문으로 새지 않게 가립니다.
 *
 * <p>마스킹 대상은 {@code docs/operations/credential-failure-policy.md} § 3 을 따릅니다. 패턴을
 * 추가하려면 정책 문서를 먼저 고치세요.
 *
 * <p>마지막 4자는 남깁니다. 운영자가 어느 자격증명인지는 알아야 하기 때문입니다. 형식이 뚜렷한
 * 값만 가리므로 {@code project-name-12345} 같은 일반 식별자는 그대로 둡니다. 이미 가려진
 * 문자열을 다시 넣어도 결과가 같아야 합니다.
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
            // service, region 을 콜론까지 먹는 문자 클래스로 두면 ARN 이 아닌 긴 입력에서 역추적이
            // 제곱으로 늡니다 (polynomial ReDoS). 두 칸을 콜론으로 끊어 분기를 없앱니다. region 은
            // IAM ARN 처럼 비어 있을 수 있습니다.
            new RedactRule(Pattern.compile("\\barn:aws:[^\\s\"':]+:[^\\s\"':]*:\\d{12}:[^\\s\"']+"), "AWS_ARN"),
            new RedactRule(Pattern.compile("(?<![0-9])\\d{12}(?![0-9])"), "AWS_ACCT"),
            new RedactRule(
                    Pattern.compile("(?i)\\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\b"), "UUID"),
            new RedactRule(Pattern.compile("\\b(?:[0-9a-f]{2}:){15}[0-9a-f]{2}\\b"), "OCI_FP"),
            new RedactRule(Pattern.compile("(?i)\\bocid1\\.[a-z]+\\.[a-z0-9._-]+"), "OCID"),
            new RedactRule(
                    // BEGIN 과 PRIVATE KEY 사이는 " RSA ", " OPENSSH " 처럼 짧습니다. [^-]+ 로 열어
                    // 두면 END 를 못 찾는 긴 입력에서 역추적이 제곱으로 늡니다. 본문도 같은 이유로
                    // 상한을 둡니다. RSA 4096 PEM 이 3.2KB 라 16KB 면 실제 키는 모두 들어옵니다.
                    Pattern.compile(
                            "-----BEGIN[A-Z ]{0,32}PRIVATE KEY-----[\\s\\S]{0,16384}?-----END[A-Z ]{0,32}PRIVATE KEY-----"),
                    "PRIVATE_KEY"));

    /**
     * JSON 키 이름으로 값을 가립니다.
     *
     * <p>{@link #RULES} 는 값의 생김새만 봅니다. AWS 시크릿 키처럼 정해진 형식이 없는 값은 그대로
     * 새어 나갑니다. 키 이름은 남겨 무엇이 가려졌는지 알 수 있게 합니다.
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
