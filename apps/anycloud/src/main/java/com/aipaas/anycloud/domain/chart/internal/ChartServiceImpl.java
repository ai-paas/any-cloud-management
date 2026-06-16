package com.aipaas.anycloud.domain.chart.internal;

import com.aipaas.anycloud.common.error.exception.CustomException;
import com.aipaas.anycloud.common.error.exception.HelmDeploymentException;
import com.aipaas.anycloud.domain.chart.ChartService;
import com.aipaas.anycloud.domain.chart.api.ChartHistoryItem;
import com.aipaas.anycloud.domain.chart.api.HelmReleaseResourceRef;
import com.aipaas.anycloud.domain.chart.api.response.ChartDeployResponse;
import com.aipaas.anycloud.domain.chart.api.response.ChartHistoryResponse;
import com.aipaas.anycloud.domain.chart.api.response.ChartReleasesResponse;
import com.aipaas.anycloud.domain.chart.api.response.ChartStatusResponse;
import com.aipaas.anycloud.domain.helmrepo.HelmRepoEntity;
import com.aipaas.anycloud.domain.helmrepo.HelmRepoService;
import com.aipaas.anycloud.domain.kube.Namespaces;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aipaas.cluster.agent.runtime.HelmReleaseService;
import io.aipaas.cluster.agent.runtime.HelmRoutingException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * <pre>
 * ClassName : ChartServiceImpl
 * Type : class
 * Description : Helm 차트 관련 기능을 구현한 서비스 클래스입니다. 이후 모든 cluster-bound
 *               operations (install / uninstall / status / history / rollback / list / resources)
 *               는 cluster agent path 전용. chart 메타데이터 조회 (list / detail / values / readme)
 *               만 외부 helm repo 에 직접 HTTP/CLI 호출.
 * Related : ChartService, ChartController, HelmReleaseService (starter)
 * </pre>
 */
/**
 * Helm release / chart operations entry. 10+ dependency 를 3 책임 group 으로 정리 :
 *
 * <ol>
 *   <li>Release lifecycle — {@code helmReleaseService} (agent), {@code chartAgentInteractions},
 *       {@code clusterAgentValueInjector}. install / upgrade / rollback / uninstall.</li>
 *   <li>Chart artifact handling — {@code chartArchiveFetcher}, {@code chartParser}. 이미
 *       {@code service/chart/support/} 로 분리됨 — service 는 위임만.</li>
 *   <li>Operation tracking + audit — {@code operationService}, {@code agentCommandRouter}.</li>
 * </ol>
 *
 * <p>support/ helper 가 이미 추출되어 있으므로 향후 facade 분해 시 1 의 release lifecycle 만
 * {@code HelmReleaseManager} 로 따로 떼면 클래스 LOC 가 절반으로 줄어든다. agentCommandRouter 의
 * 호출 점이 release lifecycle 과 강하게 결합되어 있어 같은 group 에 두는 게 자연스럽다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChartServiceImpl implements ChartService {

    private final ObjectMapper objectMapper;
    // chartName 이 cluster-agent 인 경우 backend.grpcAddr 등 자동 주입. 다른 chart 는 no-op.
    private final ClusterAgentValueInjector clusterAgentValueInjector;
    /** Day-2 Helm ops 를 agent gRPC 로 routing. session 없으면 isActiveFor=false → 503. */
    private final HelmReleaseService helmReleaseService;
    /** requireHelmAgent / wrapHelmRouting / helmCall + 503 진단 hint helper 묶음. */
    private final ChartAgentInteractions agentInteractions;
    /**
     * backend 의 helm_repo table 에서 URL lookup. agent 에 명시적 chart 다운로드 URL
     * 을 전달해 alias 해상 실패 (chart-museum-external 같이 backend 만 알고 agent 모르는 repo) 회피.
     */
    private final HelmRepoService helmRepoService;
    /**
     * backend 가 chart .tgz 를 직접 fetch 해 agent 에 push. air-gapped agent 환경
     * (backend 의 chartmuseum 이 agent pod 의 network 에서 도달 불가) 에서 install 가능.
     */
    private final ChartArchiveFetcher chartArchiveFetcher;

    /**
     * Helm chart 설치 — agent-only.
     *
     * <p>agent 의 {@code INSTALL_ADDON} (helm SDK in-cluster install) 만 사용. agent session 없거나
     * 호출 실패 시 503 AGENT_UNAVAILABLE.
     *
     * <p>cluster-agent chart 자체를 deploy 할 때는 {@link ClusterAgentValueInjector} 가 backend
     * gRPC 주소 등 필수 values 자동 주입.
     *
     * @throws CustomException {@code AGENT_UNAVAILABLE} agent session 없거나 호출 실패 시.
     */
    @Override
    public ChartDeployResponse deployChartFromYaml(
            String repositoryName,
            String chartName,
            String releaseName,
            String clusterName,
            String namespace,
            String version,
            String valuesYaml) {
        // cluster-agent chart 는 backend 가 필요값을 자동 주입 (backend.grpcAddr 등).
        // 다른 chart 는 입력 그대로 반환되므로 무해.
        String enriched = clusterAgentValueInjector.enrich(chartName, valuesYaml);
        String ns = Namespaces.defaultIfBlank(namespace);

        agentInteractions.requireHelmAgent(
                clusterName, "deploy " + repositoryName + "/" + chartName + " as " + releaseName);

        String valuesJson = yamlValuesToJson(enriched);

        // Chart 다운로드 전략 우선순위 (air-gapped agent 권장 경로):
        //   1순위: backend 가 chart .tgz 를 fetch → base64 로 agent 에 push (chartTarballBase64).
        //          agent 가 chartmuseum 도달 불가한 사내망 / private K8s 에서 정답.
        //   2순위: URL 만 전달 — agent 가 helm SDK 로 직접 다운로드. URL 이 agent 측에서 도달 가능해야.
        //   3순위: 둘 다 안 됨 — agent 의 alias resolve fallback (보통 실패).
        String resolvedRepoUrl = null;
        String chartTarballB64 = null;
        HelmRepoEntity repoEntity = null;
        try {
            repoEntity = helmRepoService.getHelmRepoEntity(repositoryName);
            if (repoEntity != null) {
                resolvedRepoUrl = repoEntity.getUrl();
            }
        } catch (Exception lookupFail) {
            log.warn("Helm repo '{}' not registered in backend — agent fallback path.", repositoryName);
        }

        if (repoEntity != null) {
            // 1순위 — backend 가 직접 fetch 해 push.
            try {
                byte[] tgz = chartArchiveFetcher.fetchArchive(repoEntity, chartName, version);
                chartTarballB64 = Base64.getEncoder().encodeToString(tgz);
                log.info(
                        "Chart pre-fetched for agent push: {}/{} v{} → {} bytes (base64 {} chars)",
                        repositoryName,
                        chartName,
                        version,
                        tgz.length,
                        chartTarballB64.length());
            } catch (Exception fetchFail) {
                // Pre-fetch 실패 — 2순위 (URL only) 로 fallback.
                log.warn(
                        "Chart pre-fetch failed for {}/{} v{} — falling back to URL-only path: {}",
                        repositoryName,
                        chartName,
                        version,
                        fetchFail.getMessage());
            }
        }

        final String resolvedRepoUrlInstall = resolvedRepoUrl;
        final String chartTarballB64Install = chartTarballB64;
        HelmReleaseService.InstalledRelease released = agentInteractions.wrapHelmRouting(
                clusterName,
                "install",
                repositoryName + "/" + chartName + " on " + clusterName,
                () -> helmReleaseService.install(
                        clusterName,
                        releaseName,
                        repositoryName + "/" + chartName,
                        resolvedRepoUrlInstall,
                        chartTarballB64Install,
                        version == null ? "" : version,
                        ns,
                        valuesJson,
                        true)); // createNamespace — chart 가 명시 ns 가 없으면 만들어줌

        log.info(
                "Helm install OK (source=AGENT): cluster={}, release={}, revision={}, status={}",
                clusterName,
                released.release(),
                released.revision(),
                released.status());
        return ChartDeployResponse.builder()
                .releaseName(released.release())
                .clusterName(clusterName)
                .namespace(released.namespace().isEmpty() ? ns : released.namespace())
                .status(released.status()) // typically "deployed"
                .detail("Helm install completed via agent (revision " + released.revision() + ")")
                .build();
    }

    /**
     * Agent dispatcher 의 INSTALL_ADDON 은 {@code values} param 을 JSON 문자열로 받음.
     * 본 ChartService 는 YAML 또는 JSON object 모두 받을 수 있어 변환 필요.
     * <p>
     * 빈 값이면 빈 문자열 반환 (agent dispatcher 가 chart default values 사용).
     */
    private String yamlValuesToJson(String yamlOrEmpty) {
        if (yamlOrEmpty == null || yamlOrEmpty.isBlank()) {
            return "";
        }
        try {
            Object parsed = new org.yaml.snakeyaml.Yaml().load(yamlOrEmpty);
            if (parsed == null) {
                return "";
            }
            return objectMapper.writeValueAsString(parsed);
        } catch (Exception e) {
            // 잘못된 YAML 이면 그대로 보낸다 — agent 가 INVALID_VALUES 로 반환할 것.
            log.warn("yamlValuesToJson: parse failed, sending raw — {}", e.getMessage());
            return yamlOrEmpty;
        }
    }

    // InMemoryMultipartFile 어댑터 제거됨 — deployChartFromYaml/deployChartFromFile 가
    // 모두 agent-only path 로 통합되어 String ↔ MultipartFile 우회가 불필요.

    /**
     * Helm chart 설치 (multipart file variant) — agent-only.
     *
     * <p>file 내용을 string 으로 읽어 {@link #deployChartFromYaml} 에 위임.
     */
    @Override
    public ChartDeployResponse deployChartFromFile(
            String repositoryName,
            String chartName,
            String releaseName,
            String clusterName,
            String namespace,
            String version,
            MultipartFile valuesFile) {
        log.info(
                "Deploy (multipart variant) chart: {}/{} as release: {} to cluster: {}",
                repositoryName,
                chartName,
                releaseName,
                clusterName);

        // 빈 파일이면 chart default values, 있으면 string 으로 변환 후 위임.
        String valuesYaml;
        if (valuesFile == null || valuesFile.isEmpty()) {
            valuesYaml = "";
        } else {
            try {
                valuesYaml = new String(valuesFile.getBytes(), java.nio.charset.StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new HelmDeploymentException("Failed to read values file: " + e.getMessage());
            }
        }
        return deployChartFromYaml(repositoryName, chartName, releaseName, clusterName, namespace, version, valuesYaml);
    }

    /**
     * Helm release status — agent-only.
     *
     * <p>agent 의 {@code GET_HELM_RELEASE_STATUS} (helm SDK {@code action.NewStatus}
     * 호출) 로 단일 release 의 status 조회. backend 의 helm CLI + kubeconfig 임시 파일 fall-through
     * 제거. cluster_agent 가 없거나 release 가 미존재면 503 / detail 에 원인.
     */
    @Override
    public ChartStatusResponse getChartStatus(String releaseName, String clusterName, String namespace) {
        log.info("Getting chart status for release: {} in cluster: {}", releaseName, clusterName);
        String targetNamespace = Namespaces.defaultIfBlank(namespace);

        agentInteractions.requireHelmAgent(clusterName, "read helm release status for " + releaseName);

        // getChartStatus 는 release 미존재 시 throw 가 아니라 UNKNOWN DTO 반환 — 다른 메서드와 다른
        // 시맨틱이라 wrapHelmRouting 미사용. classifyException 대신 즉시 DTO build.
        HelmReleaseService.ReleaseStatus status;
        try {
            status = helmReleaseService.getStatus(clusterName, targetNamespace, releaseName);
        } catch (HelmRoutingException e) {
            // release 미존재는 agent 가 HELM_NOT_FOUND 로 보냄 → CustomException 에 message 그대로.
            log.warn("Agent getStatus failed (cluster={}, release={}): {}", clusterName, releaseName, e.getMessage());
            return ChartStatusResponse.builder()
                    .releaseName(releaseName)
                    .clusterName(clusterName)
                    .namespace(targetNamespace)
                    .status("UNKNOWN")
                    .detail("Failed to get chart status: " + e.getMessage())
                    .build();
        }

        log.info(
                "Helm release status loaded (source=AGENT): cluster={}, release={}, ns={}, status={}",
                clusterName,
                releaseName,
                status.namespace(),
                status.status());
        return ChartStatusResponse.builder()
                .releaseName(releaseName)
                .clusterName(clusterName)
                .namespace(status.namespace().isEmpty() ? targetNamespace : status.namespace())
                .status(status.status())
                .detail("Release status retrieved successfully (revision " + status.revision() + ", chart "
                        + status.chart() + " " + status.version() + ", updated " + status.updated() + ")")
                .build();
    }

    /**
     * Helm release revision 이력 — agent-only.
     *
     * <p>agent 의 {@code GET_HELM_RELEASE_HISTORY} (helm SDK {@code action.NewHistory})
     * 가 직접 in-cluster 호출 → backend 의 helm CLI history + kubeconfig fall-through 제거.
     * 응답 시 raw JSON 파싱 대신 agent 가 struct field 단위로 보내 normalization 일관.
     */
    @Override
    public ChartHistoryResponse getReleaseHistory(String clusterName, String releaseName, String namespace, int max) {
        log.info("Getting history for release: {} in cluster: {} (max={})", releaseName, clusterName, max);
        String targetNamespace = Namespaces.defaultIfBlank(namespace);

        List<HelmReleaseService.HistoryRevision> revs = agentInteractions.helmCall(
                clusterName,
                "read history for",
                releaseName + " on " + clusterName,
                () -> helmReleaseService.getHistory(clusterName, targetNamespace, releaseName, max));

        List<ChartHistoryItem> revisions = new ArrayList<>(revs.size());
        for (HelmReleaseService.HistoryRevision r : revs) {
            revisions.add(ChartHistoryItem.builder()
                    .revision(r.revision())
                    .updated(r.updated())
                    .status(r.status())
                    .chart(r.chart())
                    .appVersion(r.appVersion())
                    .description(r.description())
                    .build());
        }
        log.info(
                "Helm history loaded (source=AGENT): cluster={}, release={}, count={}",
                clusterName,
                releaseName,
                revisions.size());
        return ChartHistoryResponse.builder()
                .clusterName(clusterName)
                .releaseName(releaseName)
                .namespace(targetNamespace)
                .revisions(revisions)
                .build();
    }

    /**
     * Helm release rollback — agent-only.
     *
     * <p>agent 의 {@code ROLLBACK_HELM_RELEASE} 가 helm SDK {@code action.NewRollback}
     * 호출 후 status 까지 같이 반환. backend 는 helm CLI rollback + 별도 status 조회 2-step 을
     * 한 RPC 로 대체. kubeconfig 파일 생성/삭제 surface 제거.
     */
    @Override
    public ChartStatusResponse rollbackRelease(
            String clusterName, String releaseName, int revision, String namespace, boolean waitForReady) {
        log.info(
                "Rolling back release: {} to revision={} (cluster={}, wait={})",
                releaseName,
                revision,
                clusterName,
                waitForReady);
        String targetNamespace = Namespaces.defaultIfBlank(namespace);

        HelmReleaseService.ReleaseStatus rolledBack = agentInteractions.helmCall(
                clusterName,
                "rollback",
                releaseName + " (revision=" + revision + ") on " + clusterName,
                () -> helmReleaseService.rollback(clusterName, targetNamespace, releaseName, revision, waitForReady));

        log.info(
                "Helm rollback OK (source=AGENT): cluster={}, release={}, ns={}, revision={}, status={}",
                clusterName,
                releaseName,
                rolledBack.namespace(),
                rolledBack.revision(),
                rolledBack.status());
        return ChartStatusResponse.builder()
                .releaseName(releaseName)
                .clusterName(clusterName)
                .namespace(rolledBack.namespace().isEmpty() ? targetNamespace : rolledBack.namespace())
                .status(rolledBack.status())
                .detail("Rollback completed to revision " + revision
                        + " (now at revision " + rolledBack.revision() + ", chart "
                        + rolledBack.chart() + " " + rolledBack.version() + ")")
                .build();
    }

    /**
     * Helm release uninstall — agent-only.
     *
     * <p>agent 의 {@code UNINSTALL_ADDON} dispatcher 가 helm SDK 의 KeepHistory /
     * Wait 옵션을 직접 surface — in-cluster agent 가 동일 시맨틱 제공.
     *
     * @throws CustomException {@code AGENT_UNAVAILABLE} agent session 없거나 호출 실패 시.
     */
    @Override
    public ChartStatusResponse uninstallRelease(
            String clusterName, String releaseName, String namespace, boolean keepHistory, boolean waitForReady) {
        log.info(
                "Uninstalling release: {} (cluster={}, ns={}, keepHistory={}, wait={})",
                releaseName,
                clusterName,
                namespace,
                keepHistory,
                waitForReady);
        String targetNamespace = Namespaces.defaultIfBlank(namespace);

        agentInteractions.helmCall(clusterName, "uninstall", releaseName + " on " + clusterName, () -> {
            helmReleaseService.uninstall(clusterName, releaseName, targetNamespace, keepHistory, waitForReady);
            return null;
        });

        log.info(
                "Helm uninstall OK (source=AGENT): cluster={}, release={}, ns={}, keepHistory={}, wait={}",
                clusterName,
                releaseName,
                targetNamespace,
                keepHistory,
                waitForReady);
        return ChartStatusResponse.builder()
                .releaseName(releaseName)
                .clusterName(clusterName)
                .namespace(targetNamespace)
                .status(keepHistory ? "uninstalled" : "deleted")
                .detail(keepHistory ? "Release uninstalled via agent (history kept)" : "Release uninstalled via agent")
                .build();
    }

    /**
     * 3 — Helm release UPGRADE.
     *
     * <p>deployChartFromYaml 와 거의 동일한 chart resolution (helm_repo URL lookup + chart .tgz
     * pre-fetch) — release 가 이미 존재한다는 가정만 다름. release 미존재 시 agent 가 HELM_NOT_FOUND
     * error_code 로 회신 → toClassifiedException 가 400 으로 분류.
     */
    @Override
    public ChartStatusResponse upgradeRelease(
            String clusterName,
            String releaseName,
            String repositoryName,
            String chartName,
            String version,
            String namespace,
            String valuesYaml,
            boolean atomic,
            boolean reuseValues,
            boolean resetValues) {
        log.info(
                "Upgrading release: {}/{} v{} (cluster={}, release={}, atomic={}, reuse={}, reset={})",
                repositoryName,
                chartName,
                version,
                clusterName,
                releaseName,
                atomic,
                reuseValues,
                resetValues);
        String ns = Namespaces.defaultIfBlank(namespace);

        agentInteractions.requireHelmAgent(
                clusterName, "upgrade " + repositoryName + "/" + chartName + " (" + releaseName + ")");

        String enriched = clusterAgentValueInjector.enrich(chartName, valuesYaml);
        String valuesJson = yamlValuesToJson(enriched);

        // Chart resolution — deployChartFromYaml 와 동일 우선순위 (pre-fetch → URL → alias fallback).
        String resolvedRepoUrl = null;
        String chartTarballB64 = null;
        HelmRepoEntity repoEntity = null;
        try {
            repoEntity = helmRepoService.getHelmRepoEntity(repositoryName);
            if (repoEntity != null) {
                resolvedRepoUrl = repoEntity.getUrl();
            }
        } catch (Exception lookupFail) {
            log.warn("Helm repo '{}' not registered in backend — agent fallback path.", repositoryName);
        }
        if (repoEntity != null) {
            try {
                byte[] tgz = chartArchiveFetcher.fetchArchive(repoEntity, chartName, version);
                chartTarballB64 = Base64.getEncoder().encodeToString(tgz);
                log.info(
                        "Chart pre-fetched for upgrade: {}/{} v{} → {} bytes",
                        repositoryName,
                        chartName,
                        version,
                        tgz.length);
            } catch (Exception fetchFail) {
                log.warn(
                        "Chart pre-fetch failed for {}/{} v{} — falling back to URL-only: {}",
                        repositoryName,
                        chartName,
                        version,
                        fetchFail.getMessage());
            }
        }

        final String resolvedRepoUrlFinal = resolvedRepoUrl;
        final String chartTarballB64Final = chartTarballB64;
        HelmReleaseService.InstalledRelease upgraded = agentInteractions.wrapHelmRouting(
                clusterName,
                "upgrade",
                repositoryName + "/" + chartName + " on " + clusterName,
                () -> helmReleaseService.upgrade(
                        clusterName,
                        releaseName,
                        repositoryName + "/" + chartName,
                        resolvedRepoUrlFinal,
                        chartTarballB64Final,
                        version == null ? "" : version,
                        ns,
                        valuesJson,
                        atomic,
                        reuseValues,
                        resetValues,
                        0)); // agent default timeout (600s)

        log.info(
                "Helm upgrade OK (source=AGENT): cluster={}, release={}, revision={}, status={}",
                clusterName,
                upgraded.release(),
                upgraded.revision(),
                upgraded.status());
        return ChartStatusResponse.builder()
                .releaseName(upgraded.release())
                .clusterName(clusterName)
                .namespace(upgraded.namespace().isEmpty() ? ns : upgraded.namespace())
                .status(upgraded.status())
                .detail("Helm upgrade completed via agent (revision " + upgraded.revision() + ")")
                .build();
    }

    // createKubeconfigFile / deleteKubeconfigFile 제거됨 — agent path 가 in-cluster
    // ServiceAccount token 으로 K8s API 호출하므로 임시 kubeconfig 파일이 필요 없다. backend 의
    // disk write / cleanup surface (특히 cleanup 누락 위험) 가 사라짐.

    /**
     * Cluster 의 Helm 릴리즈 목록 — agent-only (LIST_HELM_RELEASES).
     *
     * <p>helm CLI + kubeconfig fall-through 제거. agent path 가 in-cluster 의 helm SDK
     * 로 자연스럽게 모든 release 를 enumerate. CLI fallback 은 kubeconfig 생성 / process spawn /
     * temp 파일 cleanup 의 큰 surface 였는데 동일 정보를 in-cluster 한 RPC 로 얻을 수 있어 제거.
     *
     * @throws CustomException {@code AGENT_UNAVAILABLE} agent session 없거나 호출 실패 시.
     */
    @Override
    public ChartReleasesResponse getReleases(String clusterName, String namespace) {
        log.info("Getting releases for cluster: {}, namespace: {}", clusterName, namespace);

        JsonNode releasesNode = agentInteractions.helmCall(
                clusterName,
                "list helm releases",
                "on " + clusterName,
                () -> helmReleaseService.listReleases(clusterName, namespace == null ? "" : namespace));

        List<ChartReleasesResponse.ReleaseInfo> releases = parseAgentReleasesJson(releasesNode);
        log.info(
                "Helm releases loaded (source=AGENT): cluster={}, ns={}, count={}",
                clusterName,
                namespace,
                releases.size());
        return ChartReleasesResponse.builder().releases(releases).build();
    }

    /**
     * Agent 의 LIST_HELM_RELEASES 응답 ({@link HelmReleaseService#listReleases}) 을
     * {@link ChartReleasesResponse.ReleaseInfo} list 로 변환.
     * <p>
     * Agent dispatcher 출력 (dispatcher.go listHelmReleases) field names:
     *   name, namespace, chart, version, app_version, revision, status, updated.
     * CLI 의 {@code helm list -o json} field names 와 다른 부분 (e.g. revision: int vs string)
     * 도 본 변환에서 normalize.
     */
    private List<ChartReleasesResponse.ReleaseInfo> parseAgentReleasesJson(JsonNode releasesNode) {
        if (releasesNode == null || !releasesNode.isArray()) {
            return List.of();
        }
        List<ChartReleasesResponse.ReleaseInfo> out = new ArrayList<>(releasesNode.size());
        for (JsonNode r : releasesNode) {
            out.add(ChartReleasesResponse.ReleaseInfo.builder()
                    .name(r.path("name").asText(""))
                    .namespace(r.path("namespace").asText(""))
                    .chart(r.path("chart").asText(""))
                    .chartVersion(r.path("version").asText(""))
                    .revision(
                            r.path("revision").asInt(0) == 0
                                    ? r.path("revision").asText("")
                                    : String.valueOf(r.path("revision").asInt()))
                    .status(r.path("status").asText(""))
                    .updated(r.path("updated").asText(""))
                    .build());
        }
        return out;
    }

    /**
     * Helm release 의 K8s 자원 ref 목록 — agent-only.
     *
     * <p>agent path 는 in-cluster 단일 호출 — agent 없거나 실패 시 503. caller 가 재시도 / 사용자
     * degraded 알림.
     *
     * @throws CustomException {@code AGENT_UNAVAILABLE} agent session 없거나 호출 실패 시.
     */
    @Override
    public List<HelmReleaseResourceRef> getHelmResources(String clusterName, String namespace, String releaseName) {
        String ns = Namespaces.defaultIfBlank(namespace);

        List<HelmReleaseService.HelmReleaseResource> refs = agentInteractions.helmCall(
                clusterName,
                "enumerate helm release resources for",
                releaseName + " on " + clusterName,
                () -> helmReleaseService.listReleaseResources(clusterName, ns, releaseName));

        log.info(
                "Helm release resources loaded (source=AGENT): cluster={}, ns={}, release={}, count={}",
                clusterName,
                ns,
                releaseName,
                refs.size());

        List<HelmReleaseResourceRef> out = new ArrayList<>(refs.size());
        for (HelmReleaseService.HelmReleaseResource r : refs) {
            out.add(HelmReleaseResourceRef.builder()
                    .kind(r.kind())
                    .apiVersion(r.apiVersion())
                    .namespace(r.namespace())
                    .name(r.name())
                    .build());
        }
        return out;
    }

    // Agent 의 helm op 실패 분류 / agent 가용성 가드는 ChartAgentInteractions (@Component) 가 담당.
}
