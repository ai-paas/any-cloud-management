package com.aipaas.anycloud.domain.chart;

import com.aipaas.anycloud.domain.chart.api.HelmReleaseResourceRef;
import com.aipaas.anycloud.domain.chart.api.response.ChartDeployResponse;
import com.aipaas.anycloud.domain.chart.api.response.ChartHistoryResponse;
import com.aipaas.anycloud.domain.chart.api.response.ChartReleasesResponse;
import com.aipaas.anycloud.domain.chart.api.response.ChartStatusResponse;
import java.util.List;
import org.springframework.web.multipart.MultipartFile;

/**
 * Cluster 의 Helm release lifecycle — install / status / history / rollback / uninstall.
 *
 * <p>모든 메서드가 agent gRPC routing 으로 in-cluster helm SDK 호출. 이후 fabric8 / helm CLI
 * fall-through 없음 — agent session 미가용 시 503 AGENT_UNAVAILABLE.
 *
 * <p>Chart 메타데이터 조회 (index.yaml / values.yaml / README) 는 {@link ChartMetadataService} 가
 * 별도 책임 — 외부 helm repo HTTP 호출과 cluster-internal gRPC routing 의 책임 분리.
 */
public interface ChartService {

    /**
     * Helm 차트를 비동기로 배포합니다 (multipart 파일 업로드 변형).
     * UI / 큰 values 파일 케이스에 적합.
     *
     * @param repositoryName Helm repository 이름
     * @param chartName      차트 이름
     * @param releaseName    릴리즈 이름
     * @param clusterName    클러스터 이름
     * @param namespace      네임스페이스 (선택사항)
     * @param version        차트 버전 (선택사항)
     * @param valuesFile     values.yaml 파일 (선택사항)
     * @return 배포 요청 결과
     */
    ChartDeployResponse deployChartFromFile(
            String repositoryName,
            String chartName,
            String releaseName,
            String clusterName,
            String namespace,
            String version,
            MultipartFile valuesFile);

    /**
     * Helm 차트를 비동기로 배포합니다 (raw YAML 문자열 변형).
     * JSON-friendly — values.yaml 내용을 문자열로 받아 임시 파일로 변환 후 동일 흐름.
     * RESTful /v1 endpoint (JSON body) 가 사용.
     */
    ChartDeployResponse deployChartFromYaml(
            String repositoryName,
            String chartName,
            String releaseName,
            String clusterName,
            String namespace,
            String version,
            String valuesYaml);

    /** @deprecated {@link #deployChartFromFile} 사용. */
    @Deprecated
    default ChartDeployResponse deployChart(
            String repositoryName,
            String chartName,
            String releaseName,
            String clusterName,
            String namespace,
            String version,
            MultipartFile valuesFile) {
        return deployChartFromFile(repositoryName, chartName, releaseName, clusterName, namespace, version, valuesFile);
    }

    /** @deprecated {@link #deployChartFromYaml} 사용. */
    @Deprecated
    default ChartDeployResponse deployChart(
            String repositoryName,
            String chartName,
            String releaseName,
            String clusterName,
            String namespace,
            String version,
            String valuesYaml) {
        return deployChartFromYaml(repositoryName, chartName, releaseName, clusterName, namespace, version, valuesYaml);
    }

    /**
     * 배포된 차트의 상태를 조회합니다.
     *
     * @param releaseName 릴리즈 이름
     * @param clusterName 클러스터 이름
     * @param namespace   네임스페이스 (선택사항)
     * @return 배포 상태
     */
    ChartStatusResponse getChartStatus(String releaseName, String clusterName, String namespace);

    /**
     * 클러스터의 모든 Helm 릴리즈 목록을 조회합니다.
     *
     * @param clusterName 클러스터 이름
     * @param namespace 네임스페이스 (선택사항, null일 경우 모든 네임스페이스)
     * @return 릴리즈 목록
     */
    ChartReleasesResponse getReleases(String clusterName, String namespace);

    /**
     * Helm release 의 revision 이력(history) 조회.
     *
     * @param clusterName 클러스터 이름
     * @param releaseName 릴리즈 이름
     * @param namespace 네임스페이스(선택)
     * @param max 최근 N 개만 반환(<=0 이면 helm 기본)
     */
    ChartHistoryResponse getReleaseHistory(String clusterName, String releaseName, String namespace, int max);

    /**
     * Helm release 를 지정 revision 으로 rollback.
     *
     * @param clusterName 클러스터 이름
     * @param releaseName 릴리즈 이름
     * @param revision 대상 revision (0 = 직전 성공 revision)
     * @param namespace 네임스페이스(선택)
     * @param waitForReady --wait 적용 여부 (true 면 모든 리소스 ready 까지 대기)
     */
    ChartStatusResponse rollbackRelease(
            String clusterName, String releaseName, int revision, String namespace, boolean waitForReady);

    /**
     * 클러스터의 helm 릴리즈 리소스 목록을 조회합니다.
     * <p>
     * 가벼운 ref (kind/apiVersion/namespace/name) 만 반환. full spec/status 가 필요하면
     * 호출자가 ref 별로 별도 GET. agent-only path — 에서 fabric8 fall-through
     * (HelmReleaseScanner) 는 제거됨. agent session 없거나 호출 실패 시 503 AGENT_UNAVAILABLE.
     *
     * @param clusterName 클러스터 이름
     * @param namespace 네임스페이스
     * @param releaseName 릴리즈 이름
     * @return 릴리즈 자원 ref 목록
     */
    List<HelmReleaseResourceRef> getHelmResources(String clusterName, String namespace, String releaseName);

    /**
     * Helm release 를 uninstall.
     *
     * @param clusterName 클러스터 이름
     * @param releaseName 릴리즈 이름
     * @param namespace 네임스페이스 (null/blank → "default")
     * @param keepHistory true 면 helm 이 revision 이력을 보존 (--keep-history)
     * @param waitForReady true 면 모든 자원 삭제될 때까지 대기 (--wait)
     * @return uninstall 결과 요약 (status / detail)
     */
    ChartStatusResponse uninstallRelease(
            String clusterName, String releaseName, String namespace, boolean keepHistory, boolean waitForReady);

    /**
     * 3 — Helm release 를 새 chart version / values 로 업그레이드.
     *
     * <p>Install 과 동일하게 helm_repo 의 URL lookup + chart .tgz pre-fetch (air-gapped 지원).
     * Agent 측 release lock 공유로 install/upgrade/uninstall 직렬화.
     *
     * <p>release 미존재 시 503 AGENT_UNAVAILABLE 또는 400 HELM_NOT_FOUND — caller 는 install 먼저.
     *
     * @param atomic       실패 시 자동 rollback (helm CLI 의 --atomic). production 권장.
     * @param reuseValues  기존 release 의 values 보존 + 새 values merge. 부분 변경 시 유용.
     * @param resetValues  기존 values 모두 reset, chart default + 새 values 만. reuseValues 우선.
     */
    ChartStatusResponse upgradeRelease(
            String clusterName,
            String releaseName,
            String repositoryName,
            String chartName,
            String version,
            String namespace,
            String valuesYaml,
            boolean atomic,
            boolean reuseValues,
            boolean resetValues);
}
