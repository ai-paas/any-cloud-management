package com.aipaas.anycloud.domain.chart;

import com.aipaas.anycloud.domain.chart.api.response.ChartDetailResponse;
import com.aipaas.anycloud.domain.chart.api.response.ChartListResponse;
import com.aipaas.anycloud.domain.chart.api.response.ChartReadmeResponse;
import com.aipaas.anycloud.domain.chart.api.response.ChartValuesResponse;

/**
 * Helm chart 메타데이터 조회 — chart museum / OCI repo 의 {@code index.yaml} 과 {@code .tgz} 안의
 * {@code values.yaml} / {@code README.md} 를 in-process 로 파싱.
 *
 * <p>Cluster 와 무관 (agent gRPC routing 없음). 책임 분리:
 * <ul>
 *   <li>{@code ChartMetadataService} — chart 메타데이터 (본 인터페이스)
 *   <li>{@code ChartService} — cluster 의 helm 릴리즈 lifecycle (install / status / history / uninstall)
 * </ul>
 */
public interface ChartMetadataService {

    /**
     * Helm 저장소의 모든 chart 목록을 반환 — {@code <repo>/index.yaml} 을 fetch + parse.
     *
     * @param repositoryName 저장소 이름 (HelmRepoEntity.name)
     * @return chart 목록 (이름/버전/description/keywords)
     */
    ChartListResponse getChartList(String repositoryName);

    /**
     * 특정 chart 의 상세 메타데이터 — versionHistory, maintainers, dependencies 등 모두 포함.
     *
     * @param version null 또는 빈 문자열이면 latest version.
     */
    ChartDetailResponse getChartDetail(String repositoryName, String chartName, String version);

    /**
     * Chart .tgz archive 의 {@code values.yaml} 원본 텍스트를 반환 (in-process 추출).
     */
    ChartValuesResponse getChartValues(String repositoryName, String chartName, String version);

    /**
     * Chart .tgz archive 의 {@code README.md} 원본 텍스트를 반환.
     */
    ChartReadmeResponse getChartReadme(String repositoryName, String chartName, String version);
}
