package com.aipaas.anycloud.domain.vmoptions;

import java.util.Locale;
import java.util.Map;
import org.springframework.util.StringUtils;

/**
 * CPU 아키텍처 표기 정규화.
 *
 * <p>CSP 마다 같은 아키텍처를 다르게 보고한다. IBM 은 {@code amd64}, AWS 와 GCP 는
 * {@code x86_64}, Alibaba 는 {@code X86}. 한쪽 표기로만 맞춰 문자열을 그대로 비교하면
 * 나머지 CSP 에서 전부 불일치로 판정된다. 실제로 조회 API 가 요청값을 {@code x86_64} 로
 * 바꿔 전달해 IBM 이미지가 한 건도 반환되지 않았다.
 *
 * <p>표에 없는 표기는 {@code null} 이고 판정 대상에서 제외한다. 새 CSP 나 새 아키텍처가
 * 들어왔을 때 정상 조합을 차단하는 쪽보다, 거르지 못하는 쪽이 안전하다.
 */
public final class CpuArchitecture {

    private static final Map<String, String> ALIASES = Map.ofEntries(
            Map.entry("x86_64", "amd64"),
            Map.entry("x86-64", "amd64"),
            Map.entry("x86", "amd64"),
            Map.entry("x64", "amd64"),
            Map.entry("amd64", "amd64"),
            Map.entry("aarch64", "arm64"),
            Map.entry("arm64", "arm64"),
            Map.entry("arm", "arm64"),
            Map.entry("s390x", "s390x"),
            Map.entry("ppc64le", "ppc64le"),
            Map.entry("riscv64", "riscv64"));

    private CpuArchitecture() {}

    /** @return 표준 이름. 값이 없거나 알려지지 않은 표기면 null. */
    public static String canonical(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return ALIASES.get(value.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * 두 표기가 같은 아키텍처인지.
     *
     * <p>요청값이 비어 있으면 필터링하지 않는다는 뜻이므로 통과. 한쪽이라도 표에 없는
     * 표기면 판정 불가로 보고 통과.
     */
    public static boolean matches(String requested, String actual) {
        if (!StringUtils.hasText(requested)) {
            return true;
        }
        String left = canonical(requested);
        String right = canonical(actual);
        if (left == null || right == null) {
            return true;
        }
        return left.equals(right);
    }
}
