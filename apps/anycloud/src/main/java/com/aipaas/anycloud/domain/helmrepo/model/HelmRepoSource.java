package com.aipaas.anycloud.domain.helmrepo.model;

/**
 * Helm repository 의 종류.
 *
 * <p>hybrid helm-repo 지원. 동작에는 영향 없음 (URL 로 fetch) — UI filter,
 * agent push 시점에 internal/external 구분, 운영 정책 분리 등 metadata 용도.
 *
 * <p>Mirror 같은 변종 (외부 chart 를 internal 로 cache) 는 별도 enum 으로 두지 않음 — 사용 시점에서는
 * INTERNAL 과 동작 동일. "어디서 왔는가" 의 출처 추적은 {@code tags} (예: {@code mirrored-from:prometheus-community})
 * 로 표현. enum case 증가 회피 + 단순함 유지.
 */
public enum HelmRepoSource {

    /**
     * 사용자가 직접 운영하는 chart 저장소 (ChartMuseum / Harbor / OCI registry).
     * 외부 chart 의 mirror 도 이쪽 — endpoint 가 internal 인 한 동작 동일.
     */
    INTERNAL,

    /** 외부 public chart 저장소 (helm.sh / github pages / public OCI). */
    EXTERNAL
}
