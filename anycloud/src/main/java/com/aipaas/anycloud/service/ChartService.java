package com.aipaas.anycloud.service;

import com.aipaas.anycloud.model.dto.response.*;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

/**
 * <pre>
 * ClassName : ChartService
 * Type : interface
 * Description : Helm 차트 관련 기능을 정리한 인터페이스입니다.
 * Related : ChartController, ChartServiceImpl
 * </pre>
 */
@Component
public interface ChartService {

    /**
     * 지정된 repository의 차트 목록을 조회합니다.
     *
     * @param repositoryName Helm repository 이름
     * @return 차트 목록
     */
    ChartListDto getChartList(String repositoryName);

    /**
     * 지정된 repository chart의 상세 정보를 조회합니다.
     *
     * @param repositoryName Helm repository 이름
     * @param chartName Helm chart 이름
     * @return 차트 상세 정보
     */
    ChartDetailDto getChartDetail(String repositoryName, String chartName);

    /**
     * 지정된 차트의 values.yaml 내용을 조회합니다.
     *
     * @param repositoryName Helm repository 이름
     * @param chartName      차트 이름
     * @param version        차트 버전 (선택사항, null일 경우 최신 버전)
     * @return values.yaml 내용
     */
    ChartValuesDto getChartValues(String repositoryName, String chartName, String version);

    /**
     * 지정된 차트의 README.md 내용을 조회합니다.
     *
     * @param repositoryName Helm repository 이름
     * @param chartName      차트 이름
     * @param version        차트 버전 (선택사항, null일 경우 최신 버전)
     * @return README.md 내용
     */
    ChartReadmeDto getChartReadme(String repositoryName, String chartName, String version);

    /**
     * Helm 차트를 비동기로 배포합니다.
     *
     * @param repositoryName Helm repository 이름
     * @param chartName      차트 이름
     * @param releaseName    릴리즈 이름
     * @param clusterId      클러스터 ID
     * @param namespace      네임스페이스 (선택사항)
     * @param version        차트 버전 (선택사항)
     * @param valuesFile     values.yaml 파일 (선택사항)
     * @return 배포 요청 결과
     */
    ChartDeployResponseDto deployChart(String repositoryName, String chartName, String releaseName, String clusterId, String namespace, String version, MultipartFile valuesFile);

    /**
     * 배포된 차트의 상태를 조회합니다.
     *
     * @param releaseName 릴리즈 이름
     * @param clusterId   클러스터 ID
     * @param namespace   네임스페이스 (선택사항)
     * @return 배포 상태
     */
    ChartDeployResponseDto getChartStatus(String releaseName, String clusterId, String namespace);

    /**
     * 클러스터의 모든 Helm 릴리즈 목록을 조회합니다.
     *
     * @param clusterId 클러스터 ID
     * @param namespace 네임스페이스 (선택사항, null일 경우 모든 네임스페이스)
     * @return 릴리즈 목록
     */
    ChartReleasesResponseDto getReleases(String clusterId, String namespace);
}
