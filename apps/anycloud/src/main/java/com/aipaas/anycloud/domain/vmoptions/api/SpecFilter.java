package com.aipaas.anycloud.domain.vmoptions.api;

/**
 * 기본값을 고를 때 쓸 조건.
 *
 * <p>검증용 생성에는 GPU 또는 상위 스펙 지정이 필요. 조건을 코드에 고정하면 화면에서 값을
 * 직접 입력해야 함.
 */
public record SpecFilter(int minVcpu, double minMemoryGb, boolean gpu) {

    /** control-plane 이 뜨는 최소선. 더 작으면 생성은 되고 클러스터가 안 선다. */
    public static final SpecFilter DEFAULT = new SpecFilter(2, 4.0, false);

    public static SpecFilter of(Integer minVcpu, Double minMemoryGb, Boolean gpu) {
        return new SpecFilter(
                minVcpu == null || minVcpu <= 0 ? DEFAULT.minVcpu() : minVcpu,
                minMemoryGb == null || minMemoryGb <= 0 ? DEFAULT.minMemoryGb() : minMemoryGb,
                Boolean.TRUE.equals(gpu));
    }
}
