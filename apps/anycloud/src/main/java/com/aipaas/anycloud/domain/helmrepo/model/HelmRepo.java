package com.aipaas.anycloud.domain.helmrepo.model;

import java.time.LocalDateTime;

/**
 * Helm repository (ChartMuseum / OCI / Bitnami 등) 의 immutable 도메인 표현.
 *
 * @param source  Repo 출처 분류 (INTERNAL / EXTERNAL). UI 필터 및 정책 분기용.
 * @param tags    Free-form comma-separated tags. UI 필터용.
 */
public record HelmRepo(
        String id,
        String name,
        String url,
        String username,
        String password,
        String caFile,
        Boolean insecureSkipTlsVerify,
        HelmRepoSource source,
        String tags,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    /** Basic auth 자격증명 보유 여부. */
    public boolean hasCredentials() {
        return username != null && !username.isBlank() && password != null && !password.isBlank();
    }

    /** TLS 검증 비활성 (self-signed 대상). production 에서는 false 권장. */
    public boolean isTlsVerificationDisabled() {
        return Boolean.TRUE.equals(insecureSkipTlsVerify);
    }
}
