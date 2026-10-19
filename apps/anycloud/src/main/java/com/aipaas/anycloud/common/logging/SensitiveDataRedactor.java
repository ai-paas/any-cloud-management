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
            // service, region 을 콜론까지 먹는 문자 클래스로 두면 ARN 이 아닌 긴 입력에서 역추적이
            // 제곱으로 는다 (polynomial ReDoS). 두 칸을 콜론으로 끊어 분기를 없앤다. region 은 IAM
            // ARN 처럼 비어 있을 수 있다.
            new RedactRule(Pattern.compile("\\barn:aws:[^\\s\"':]+:[^\\s\"':]*:\\d{12}:[^\\s\"']+"), "AWS_ARN"),
            new RedactRule(Pattern.compile("(?<![0-9])\\d{12}(?![0-9])"), "AWS_ACCT"),
            new RedactRule(
                    Pattern.compile("(?i)\\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\b"), "UUID"),
            new RedactRule(Pattern.compile("\\b(?:[0-9a-f]{2}:){15}[0-9a-f]{2}\\b"), "OCI_FP"),
            new RedactRule(Pattern.compile("(?i)\\bocid1\\.[a-z]+\\.[a-z0-9._-]+"), "OCID"),
            new RedactRule(
                    // BEGIN 과 PRIVATE KEY 사이는 " RSA ", " OPENSSH " 처럼 짧다. [^-]+ 로 열어 두면
                    // END 를 못 찾는 긴 입력에서 역추적이 제곱으로 는다. 본문도 같은 이유로 상한을 둔다
                    // — RSA 4096 PEM 이 3.2KB 라 16KB 면 실제 키는 모두 들어온다.
                    Pattern.compile(
                            "-----BEGIN[A-Z ]{0,32}PRIVATE KEY-----[\\s\\S]{0,16384}?-----END[A-Z ]{0,32}PRIVATE KEY-----"),
                    "PRIVATE_KEY"));

    /**
     * JSON 키 이름으로 값을 가린다.
     *
     * <p>{@link #RULES} 는 값의 생김새만 본다. AWS 시크릿 키처럼 형식이 없는 값은 그대로 새어
     * 나간다. 어떤 값인지는 옆에 붙은 키 이름이 말해주므로 이름으로 판단한다. 키 이름은 남겨
     * 무엇이 가려졌는지 알 수 있게 한다.
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
