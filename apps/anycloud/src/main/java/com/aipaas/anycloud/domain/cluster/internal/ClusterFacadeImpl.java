package com.aipaas.anycloud.domain.cluster.internal;

import com.aipaas.anycloud.domain.cluster.ClusterFacade;
import com.aipaas.anycloud.domain.cluster.ClusterProvider;
import com.aipaas.anycloud.domain.cluster.ClusterService;
import com.aipaas.anycloud.domain.cluster.api.request.CreateClusterRequest;
import com.aipaas.anycloud.domain.cluster.api.request.PatchClusterRequest;
import com.aipaas.anycloud.domain.cluster.api.response.UnifiedClusterResponse;
import com.aipaas.anycloud.domain.operation.Operation;
import com.aipaas.anycloud.domain.operation.OperationEntity;
import com.aipaas.anycloud.domain.operation.OperationService;
import com.aipaas.anycloud.domain.operation.mapper.OperationMapper;
import com.aipaas.anycloud.domain.operation.model.OperationType;
import com.aipaas.anycloud.domain.provisioning.VmClusterEntity;
import com.aipaas.anycloud.domain.provisioning.VmClusterRepository;
import com.aipaas.anycloud.domain.provisioning.VmClusterService;
import io.aipaas.cluster.agent.runtime.AgentHealthService;
import io.aipaas.cluster.agent.runtime.ClusterHealth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 통합 cluster facade. {@code /v1/clusters} 컨트롤러가 사용하는 단일 진입점.
 *
 * <p>create() 의 source 분기는 {@link ClusterProvider} strategy 로 위임. list / patch / delete /
 * createOperation 은 source 별 분기가 단순하거나 vm-only 라 그대로 유지.
 *
 * <p>책임 group (7 deps) :
 * <ol>
 *   <li>Source dispatch — {@code providersBySource} ({@link ClusterProvider} strategy).
 *       create / patch / delete 가 source 별 위임.</li>
 *   <li>Paged read — {@code vmClusterService}, {@code clusterService}, {@code agentHealthService}.
 *       {@link #listPaged}, {@link #pageSingleSource}, {@link #listVm}, {@link #listRegistered} +
 *       {@code Cursor} record. 이미 helper 로 분리됨.</li>
 *   <li>Operation lifecycle — {@code operationService}, {@code operationMapper}.
 *       createOperation / domain 변형.</li>
 *   <li>Repository fallback — {@code vmClusterRepository}. getOne 단일 사이트, 추출 무가치.</li>
 * </ol>
 *
 * <p>향후 controller 가 source-별 service 직접 호출하면 본 facade 는 paged read facade 만 남는다.
 */
@Slf4j
@Service
public class ClusterFacadeImpl implements ClusterFacade {

    private final VmClusterService vmClusterService;
    private final ClusterService clusterService;
    private final OperationService operationService;
    private final VmClusterRepository vmClusterRepository;
    private final AgentHealthService agentHealthService;
    private final Map<String, ClusterProvider> providersBySource;
    private final OperationMapper operationMapper;

    public ClusterFacadeImpl(
            VmClusterService vmClusterService,
            ClusterService clusterService,
            OperationService operationService,
            VmClusterRepository vmClusterRepository,
            AgentHealthService agentHealthService,
            List<ClusterProvider> providers,
            OperationMapper operationMapper) {
        this.vmClusterService = vmClusterService;
        this.clusterService = clusterService;
        this.operationService = operationService;
        this.vmClusterRepository = vmClusterRepository;
        this.agentHealthService = agentHealthService;
        this.operationMapper = operationMapper;
        this.providersBySource = providers.stream()
                .collect(Collectors.toMap(p -> p.source().toLowerCase(Locale.ROOT), Function.identity()));
        log.info(
                "ClusterFacadeImpl: registered {} cluster providers: {}",
                providersBySource.size(),
                providersBySource.keySet());
    }

    @Override
    public PagedClusters listPaged(
            String source, String provider, String environment, String status, int pageSize, String pageToken) {
        // Source filter 가 있으면 그 source 만 fetch 하고 source-prefixed cursor 사용. 미명시 시 두 source
        // 모두 fetch 한 뒤 createdAt 내림차순 merge — UI 의 "최신 cluster 가 위로" UX 충족. interleave
        // 결과는 단일 offset cursor 로 round-trip.
        if (source != null) {
            return pageSingleSource(source, provider, environment, status, pageSize, pageToken);
        }

        List<UnifiedClusterResponse> merged = new ArrayList<>(listVm(provider, environment, status));
        merged.addAll(listRegistered(provider));
        merged.sort(Comparator.comparing(
                UnifiedClusterResponse::createdAt, Comparator.nullsLast(Comparator.reverseOrder())));

        int offset = parseOffsetToken(pageToken);
        int from = Math.min(offset, merged.size());
        int to = Math.min(from + pageSize, merged.size());
        List<UnifiedClusterResponse> page = merged.subList(from, to);
        String nextToken = to < merged.size() ? String.valueOf(to) : null;
        return new PagedClusters(page, nextToken, (long) merged.size());
    }

    private PagedClusters pageSingleSource(
            String source, String provider, String environment, String status, int pageSize, String pageToken) {
        String normalized = source.toLowerCase(Locale.ROOT);
        Cursor cursor = Cursor.parse(pageToken, normalized);
        String activeSource = cursor.source() != null ? cursor.source() : normalized;

        List<UnifiedClusterResponse> items;
        if ("vm".equals(activeSource)) {
            items = listVm(provider, environment, status);
        } else if ("registered".equals(activeSource)) {
            items = listRegistered(provider);
        } else {
            return new PagedClusters(List.of(), null, 0L);
        }
        int from = Math.min(cursor.offset(), items.size());
        int to = Math.min(from + pageSize, items.size());
        String nextToken = to < items.size() ? activeSource + ":" + to : null;
        return new PagedClusters(items.subList(from, to), nextToken, (long) items.size());
    }

    private static int parseOffsetToken(String token) {
        if (token == null || token.isBlank()) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(token.trim()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * VmClusterService listVmClusters 결과를 UnifiedClusterResponse 로 변환. list item 이 이미 step
     * field 들을 포함 (single query) — per-cluster 재조회 (N+1) 금지.
     */
    private List<UnifiedClusterResponse> listVm(String provider, String environment, String status) {
        return vmClusterService.listVmClusters(provider, environment, status).stream()
                .map(item -> UnifiedClusterResponse.builder()
                        .source("vm")
                        .clusterName(item.getClusterName())
                        // VM provision cluster 자체가 linked vm — UI cross-link 일관성.
                        .linkedVmName(item.getClusterName())
                        .provider(item.getClusterProvider())
                        .region(item.getRegion())
                        .environment(item.getEnvironment())
                        .status(item.getStatus())
                        .createdAt(item.getCreatedAt())
                        .lastError(item.getLastError())
                        .workflowProgress(UnifiedClusterResponse.WorkflowProgress.builder()
                                .currentStep(item.getCurrentWorkflowStep())
                                .lastSuccessfulStep(item.getLastSuccessfulStep())
                                .percent(computePercentFromNames(item.getLastSuccessfulStep(), item.getStatus()))
                                .stepStartedAt(item.getStepStartedAt())
                                .retryCount(item.getWorkflowRetryCount())
                                .subStep(item.getCurrentSubStep())
                                .subStepStartedAt(item.getSubStepStartedAt())
                                .lastErrorCode(item.getLastErrorCode())
                                .build())
                        .build())
                .toList();
    }

    private List<UnifiedClusterResponse> listRegistered(String provider) {
        return clusterService.findAllDomain().stream()
                .filter(c -> provider == null || provider.equalsIgnoreCase(c.clusterProvider()))
                .map(this::toRegisteredDto)
                .toList();
    }

    /** Cursor — pageToken 인코딩. invalid token 은 첫 페이지로 fallback. */
    private record Cursor(String source, int offset) {
        static Cursor parse(String token, String filterSource) {
            if (token == null || token.isBlank()) {
                return new Cursor(filterSource == null ? null : filterSource.toLowerCase(Locale.ROOT), 0);
            }
            int colon = token.indexOf(':');
            if (colon <= 0) {
                return new Cursor(null, 0);
            }
            String src = token.substring(0, colon).toLowerCase(Locale.ROOT);
            if (!"vm".equals(src) && !"registered".equals(src)) {
                return new Cursor(null, 0);
            }
            int off;
            try {
                off = Integer.parseInt(token.substring(colon + 1));
            } catch (NumberFormatException e) {
                return new Cursor(src, 0);
            }
            return new Cursor(src, Math.max(0, off));
        }
    }

    @Override
    public List<UnifiedClusterResponse> list(String source, String provider, String environment, String status) {
        List<UnifiedClusterResponse> result = new ArrayList<>();
        if (source == null || "vm".equalsIgnoreCase(source)) {
            result.addAll(listVm(provider, environment, status));
        }
        if (source == null || "registered".equalsIgnoreCase(source)) {
            result.addAll(listRegistered(provider));
        }
        return result;
    }

    @Override
    public UnifiedClusterResponse getOne(String clusterName) {
        // VM cluster 우선 조회.
        var vmOpt = vmClusterRepository.findFirstByClusterNameOrderByCreatedAtDesc(clusterName);
        if (vmOpt.isPresent()) {
            VmClusterEntity v = vmOpt.get();
            return UnifiedClusterResponse.builder()
                    .source("vm")
                    .clusterName(v.getClusterName())
                    .linkedVmName(v.getClusterName())
                    .provider(v.getClusterProvider())
                    .region(v.getRegion())
                    .environment(v.getEnvironment())
                    .status(
                            v.getProvisioningStatus() == null
                                    ? null
                                    : v.getProvisioningStatus().name())
                    .createdAt(v.getCreatedAt())
                    .readyAt(v.getReadyAt())
                    .lastError(v.getLastError())
                    .workflowProgress(buildWorkflowProgress(v.getClusterName()))
                    .build();
        }
        // fallback: registered cluster (domain).
        return clusterService
                .findDomainById(clusterName)
                .map(this::toRegisteredDto)
                .orElseThrow(
                        () -> new com.aipaas.anycloud.common.error.exception.ClusterNotFoundException(clusterName));
    }

    /**
     * source ("vm" | "registered") 별 ClusterProvider 에 위임. 알 수 없는 source 는 즉시
     * {@link IllegalArgumentException} 으로 400. 신규 source 추가 시 {@link ClusterProvider}
     * 구현체만 등록하면 별도 코드 변경 없음.
     */
    @Override
    public OperationEntity create(CreateClusterRequest request) {
        String sourceKey =
                request.getSource() == null ? "" : request.getSource().name().toLowerCase(Locale.ROOT);
        ClusterProvider provider = providersBySource.get(sourceKey);
        if (provider == null) {
            throw new IllegalArgumentException(
                    "Unsupported cluster source: '" + sourceKey + "'. Allowed: " + providersBySource.keySet());
        }
        return provider.create(request);
    }

    @Override
    public OperationEntity patch(String clusterName, PatchClusterRequest request) {
        if (request == null || request.getSpec() == null) {
            throw new IllegalArgumentException("spec is required");
        }
        PatchClusterRequest.Spec spec = request.getSpec();
        if (spec.getWorkerCount() == null) {
            throw new IllegalArgumentException("spec.workerCount is required");
        }

        // SCALE: (1) drain excess workers (down 시), (2) pulumi up (config + apply) → 2 단계.
        OperationEntity op = operationService.start(
                OperationType.SCALE_CLUSTER,
                "cluster",
                clusterName,
                "{\"workerCount\":" + spec.getWorkerCount() + "}",
                2);
        vmClusterService.scaleVmCluster(clusterName, spec.getWorkerCount());
        operationService.markRunning(op.getId());
        return op;
    }

    @Override
    public OperationEntity delete(String clusterName) {
        OperationEntity op = operationService.start(OperationType.DELETE_CLUSTER, "cluster", clusterName, null, 1);
        // Source 분기 — list/getOne 처럼 vm 우선, 없으면 registered 로 위임.
        boolean isVm = vmClusterRepository
                .findFirstByClusterNameOrderByCreatedAtDesc(clusterName)
                .isPresent();
        if (isVm) {
            var status = vmClusterService.deleteVmCluster(clusterName);
            if (status != org.springframework.http.HttpStatus.ACCEPTED) {
                return operationService.complete(op.getId(), "{\"noop\":true}");
            }
        } else {
            // registered (ClusterEntity) — clusterService 의 deleteCluster 가 ClusterNotFoundException
            // 으로 404 처리. 정상 삭제 시 200 status code 이지만 unified 입장에선 LRO 형식 유지를
            // 위해 ACCEPTED + Operation 반환.
            clusterService.deleteCluster(clusterName);
        }
        operationService.markRunning(op.getId());
        return op;
    }

    @Override
    public OperationEntity createOperation(String clusterName, String type) {
        String normalized = type == null ? "" : type.toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "retryworkflow" -> {
                OperationEntity op =
                        operationService.start(OperationType.RETRY_WORKFLOW, "cluster", clusterName, null, 1);
                vmClusterService.retryVmClusterWorkflow(clusterName);
                operationService.markRunning(op.getId());
                yield op;
            }
            case "retryregistration" -> {
                OperationEntity op =
                        operationService.start(OperationType.RETRY_REGISTRATION, "cluster", clusterName, null, 1);
                vmClusterService.retryVmClusterRegistration(clusterName);
                operationService.markRunning(op.getId());
                yield op;
            }
            case "refreshstatus" -> {
                OperationEntity op =
                        operationService.start(OperationType.REFRESH_STATUS, "cluster", clusterName, null, 1);
                clusterService.refreshClusterStatus(clusterName);
                operationService.complete(op.getId(), null);
                yield op;
            }
            default -> throw new IllegalArgumentException("Unsupported operation type: " + type
                    + ". Allowed: retryWorkflow, retryRegistration, refreshStatus");
        };
    }

    // ===== domain return =====

    @Override
    public Operation createDomain(CreateClusterRequest request) {
        return operationMapper.toDomain(create(request));
    }

    @Override
    public Operation patchDomain(String clusterName, PatchClusterRequest request) {
        return operationMapper.toDomain(patch(clusterName, request));
    }

    @Override
    public Operation deleteDomain(String clusterName) {
        return operationMapper.toDomain(delete(clusterName));
    }

    @Override
    public Operation createOperationDomain(String clusterName, String type) {
        return operationMapper.toDomain(createOperation(clusterName, type));
    }

    @Override
    public boolean checkConnectivity(String clusterName) {
        return clusterService.testClusterConnection(clusterName);
    }

    private UnifiedClusterResponse toRegisteredDto(com.aipaas.anycloud.domain.cluster.model.Cluster c) {
        ClusterHealth health = agentHealthService.getHealth(c.id());
        // 1:1 link — VmClusterEntity 가 같은 이름으로 존재하면 VM provision 으로 만들어진 cluster.
        // UI 가 cluster 상세에서 VM 메뉴로 cross-link 시 사용.
        String linkedVm = vmClusterRepository
                        .findFirstByClusterNameOrderByCreatedAtDesc(c.id())
                        .isPresent()
                ? c.id()
                : null;
        return UnifiedClusterResponse.builder()
                .source("registered")
                .clusterName(c.id())
                .linkedVmName(linkedVm)
                .provider(c.clusterProvider())
                .environment(null)
                .status(c.status())
                // Chronological merge 동작 조건 — 두 source 모두 createdAt 필요. ZonedDateTime
                // → LocalDateTime 변환 시 zone 정보 손실해도 UI 정렬 / 표시 의미는 동일.
                .createdAt(c.createdAt() == null ? null : c.createdAt().toLocalDateTime())
                .hasGpuNodes(c.hasGpuNodes())
                .agentConnectivity(deriveConnectivity(health))
                .agentHeartbeatSecondsAgo(health.lastSeenSecondsAgo())
                .agentHealthSummary(health.summary())
                .build();
    }

    /**
     * starter 의 {@link ClusterHealth} 를 unified 응답용 4단계 상태로 압축. UI 가 status 와는
     * 별개 차원으로 agent ↔ backend 연결성을 한눈에 표시할 수 있도록 derived.
     *
     * <ul>
     *   <li>NOT_REGISTERED — ClusterAgent row 없음 (agent 미설치 또는 미등록)</li>
     *   <li>CONNECTED — DB ACTIVE + gRPC stream live + heartbeat fresh (≤90s default)</li>
     *   <li>DEGRADED — agent 등록되어 있고 stream 도 살아있으나 heartbeat 가 stale</li>
     *   <li>DISCONNECTED — stream 끊김 또는 agent REVOKED/FAILED</li>
     * </ul>
     *
     * <p>JWT bug 시나리오 (agent CrashLoopBackOff) → stream 끊김 → DISCONNECTED 로 표기.
     */
    private static String deriveConnectivity(ClusterHealth h) {
        if (!h.hasAgent()) {
            return "NOT_REGISTERED";
        }
        if (h.healthy()) {
            return "CONNECTED";
        }
        // stream 살아있으나 heartbeat stale → degraded
        if (h.streamActive()) {
            return "DEGRADED";
        }
        return "DISCONNECTED";
    }

    /**
     * VM cluster 의 in-flight workflow 단계를 UI 노출용으로 압축. cluster 의 latest row 를 조회해
     * step / lastSuccessful / percent / stepStartedAt / retryCount 추출. 미존재 시 null.
     */
    private UnifiedClusterResponse.WorkflowProgress buildWorkflowProgress(String clusterName) {
        return vmClusterRepository
                .findFirstByClusterNameOrderByCreatedAtDesc(clusterName)
                .map(v -> {
                    String currentStep = v.getCurrentWorkflowStep() == null
                            ? null
                            : v.getCurrentWorkflowStep().name();
                    String lastSuccessful = v.getLastSuccessfulStep() == null
                            ? null
                            : v.getLastSuccessfulStep().name();
                    return UnifiedClusterResponse.WorkflowProgress.builder()
                            .currentStep(currentStep)
                            .lastSuccessfulStep(lastSuccessful)
                            .percent(computePercent(lastSuccessful, v.getProvisioningStatus()))
                            .stepStartedAt(resolveStepStartedAt(v))
                            .retryCount(v.getWorkflowRetryCount())
                            .subStep(v.getCurrentSubStep())
                            .subStepStartedAt(v.getSubStepStartedAt())
                            .lastErrorCode(v.getLastErrorCode())
                            .build();
                })
                .orElse(null);
    }

    /**
     * lastSuccessfulStep + 현재 status 로 percent 매핑. READY = 100, terminal failure = 마지막 step 까지.
     * 3-step 흐름 가정 (PROVISION → BOOTSTRAP → VERIFY).
     */
    private static Integer computePercent(
            String lastSuccessful, com.aipaas.anycloud.domain.provisioning.model.VmClusterStatus status) {
        return computePercentFromNames(lastSuccessful, status == null ? null : status.name());
    }

    /** String 기반 변형 — list item (이미 String 으로 직렬화된 DTO) 에서 사용. */
    private static Integer computePercentFromNames(String lastSuccessful, String statusName) {
        if ("READY".equals(statusName)) {
            return 100;
        }
        if (lastSuccessful == null) {
            return 0;
        }
        return switch (lastSuccessful) {
            case "PROVISION" -> 33;
            case "BOOTSTRAP" -> 66;
            case "VERIFY" -> 100;
            default -> 0;
        };
    }

    private static java.time.LocalDateTime resolveStepStartedAt(VmClusterEntity v) {
        if (v.getCurrentWorkflowStep() == null) {
            return null;
        }
        return switch (v.getCurrentWorkflowStep()) {
            case PROVISION -> v.getProvisioningStartedAt();
            case BOOTSTRAP -> v.getBootstrappingStartedAt();
            case VERIFY -> v.getVerifyingStartedAt();
            case DESTROY -> v.getDeletingStartedAt();
        };
    }
}
