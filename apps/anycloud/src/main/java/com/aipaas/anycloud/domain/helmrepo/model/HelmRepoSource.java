package com.aipaas.anycloud.domain.helmrepo.model;

/** Helm repository 의 종류. */
public enum HelmRepoSource {

    /**
     * 사용자가 직접 운영하는 chart 저장소 (ChartMuseum / Harbor / OCI registry).
     * 외부 chart 의 mirror 도 이쪽 — endpoint 가 internal 인 한 동작 동일.
     */
    INTERNAL,

    /** 외부 public chart 저장소 (helm.sh / github pages / public OCI). */
    EXTERNAL
}
