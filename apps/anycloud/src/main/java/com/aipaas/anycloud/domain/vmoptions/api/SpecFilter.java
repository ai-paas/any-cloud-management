package com.aipaas.anycloud.domain.vmoptions.api;

/**
 * 기본값을 고를 때 쓸 조건.
 *
 * <p>검증용으로 띄울 때 "GPU 로 한 대" 나 "조금 큰 것으로" 가 필요하다. 조건을 코드에 박아 두면
 * 그때마다 화면이 값을 손으로 맞춰야 한다.
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
