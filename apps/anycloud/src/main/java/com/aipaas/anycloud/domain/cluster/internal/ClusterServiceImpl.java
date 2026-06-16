package com.aipaas.anycloud.domain.cluster.internal;

import com.aipaas.anycloud.common.error.enums.ErrorCode;
import com.aipaas.anycloud.common.error.exception.ClusterNotFoundException;
import com.aipaas.anycloud.common.error.exception.CustomException;
import com.aipaas.anycloud.common.error.exception.EntityNotFoundException;
import com.aipaas.anycloud.domain.agent.ClusterAgentRepository;
import com.aipaas.anycloud.domain.cluster.AgentBootstrapKubeClient;
import com.aipaas.anycloud.domain.cluster.ClusterEntity;
import com.aipaas.anycloud.domain.cluster.ClusterRepository;
import com.aipaas.anycloud.domain.cluster.ClusterService;
import com.aipaas.anycloud.domain.cluster.api.request.CreateClusterDto;
import com.aipaas.anycloud.domain.cluster.api.request.UpdateClusterDto;
import com.aipaas.anycloud.domain.cluster.mapper.ClusterMapper;
import com.aipaas.anycloud.domain.cluster.model.Cluster;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * <pre>
 * ClassName : clusterServiceImpl
 * Type : class
 * Description : 쿠버네티스 클러스터와 관련된 서비스 구현과 관련된 함수를 포함하고 있는 클래스입니다.
 * Related : ClusterController, ClusterService
 * </pre>
 */
/**
 * Registered cluster CRUD + connectivity. 8+ dependency 를 3 책임 group 으로 정리:
 *
 * <ol>
 *   <li>CRUD / domain mapping — {@code clusterRepository}, {@code clusterMapper}.</li>
 *   <li>Connectivity probing — {@code bootstrapKubeClient}, {@code clusterAgentRepository},
 *       {@code connectivityService}. agent-mediated 우선 + fabric8 fallback. 향후 별도
 *       {@code ClusterReachabilityChecker} 로 추출 가능.</li>
 *   <li>Observability — {@code meterRegistry} (hit/miss counter). 단일 카운터라 추출 무가치.</li>
 * </ol>
 *
 * <p>class-level {@code @Transactional(readOnly = true)} — write 메서드만 명시적으로 override.
 */
@Slf4j
@Service("clusterServiceImpl")
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class ClusterServiceImpl implements ClusterService {

    private final ClusterRepository clusterRepository;
    private final ClusterMapper clusterMapper;
    /** Bootstrap-only fabric8 client — agent 미설치/inactive cluster 의 connectivity + version 확인. */
    private final AgentBootstrapKubeClient bootstrapKubeClient;
    // Agent-mediated health/version 우선. fabric8 fallback 유지 — agent 미설치 / 미활성 cluster 호환.
    private final ClusterAgentRepository clusterAgentRepository;
    private final MeterRegistry meterRegistry;
    /** connectivity / status sync 로직 분리. 본 service 는 CRUD + 검증 중심. */
    private final com.aipaas.anycloud.domain.cluster.ClusterConnectivityService connectivityService;

    /**
     * Cluster create 의 latency / outcome 추적용 metric.
     * <ul>
     *   <li>{@code anycloud.cluster.create.duration} — Timer (success path only)</li>
     *   <li>{@code anycloud.cluster.create.total{outcome="success|duplicate|invalid|error"}} — Counter</li>
     * </ul>
     */
    private Timer createTimer;

    private Counter createSuccess;
    private Counter createDuplicate;
    private Counter createInvalid;
    private Counter createError;

    /** getClusterEntity 의 hit / miss 추적 metric. */
    private Counter getClusterEntityExact;

    private Counter getClusterEntityMiss;
    // getClusterEntityLenientHit 제거 (fallback 코드 자체 제거됨).

    @PostConstruct
    void initMetrics() {
        this.createTimer = Timer.builder("anycloud.cluster.create.duration")
                .description("Cluster create end-to-end latency (success path)")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry);
        this.createSuccess = Counter.builder("anycloud.cluster.create.total")
                .tag("outcome", "success")
                .description("Cluster create success")
                .register(meterRegistry);
        this.createDuplicate = Counter.builder("anycloud.cluster.create.total")
                .tag("outcome", "duplicate")
                .description("Cluster create duplicate (409)")
                .register(meterRegistry);
        this.createInvalid = Counter.builder("anycloud.cluster.create.total")
                .tag("outcome", "invalid")
                .description("Cluster create invalid input (400)")
                .register(meterRegistry);
        this.createError = Counter.builder("anycloud.cluster.create.total")
                .tag("outcome", "error")
                .description("Cluster create error (5xx)")
                .register(meterRegistry);

        // fallback observability.
        this.getClusterEntityExact = Counter.builder("anycloud.cluster.get.total")
                .tag("outcome", "exact")
                .description("getClusterEntity: findById exact hit")
                .register(meterRegistry);
        // getClusterEntityLenientHit counter 제거 (lenient fallback 코드 제거됨).
        this.getClusterEntityMiss = Counter.builder("anycloud.cluster.get.total")
                .tag("outcome", "miss")
                .description("getClusterEntity: cluster not found")
                .register(meterRegistry);
    }

    /**
     * [ClusterServiceImpl] 쿠버네티스 클러스터 전체 목록 함수
     *
     * @return 전체 쿠버네티스 클러스터 목록을 반환합니다.
     */
    public List<ClusterEntity> getClusterEntities() {
        return clusterRepository.findAll();
    }

    @Override
    public List<ClusterEntity> getClusterEntitiesByStatus(
            com.aipaas.anycloud.domain.cluster.model.ClusterStatus status) {
        return clusterRepository.findAllByStatus(status);
    }

    /**
     * [ClusterServiceImpl] 클러스터 단일 조회 함수.
     *
     * <p>{@code findById(name)} primary key 정확 매칭 — miss 시 즉시 {@link ClusterNotFoundException} throw
     * (caller controller 가 404 매핑).
     */
    public ClusterEntity getClusterEntity(String clusterName) {
        var exact = clusterRepository.findById(clusterName);
        if (exact.isPresent()) {
            if (getClusterEntityExact != null) getClusterEntityExact.increment();
            return exact.get();
        }
        if (getClusterEntityMiss != null) getClusterEntityMiss.increment();
        log.debug("getClusterEntity: '{}' not found (hex={})", clusterName, toHex(clusterName));
        throw new ClusterNotFoundException(clusterName);
    }

    @Override
    // class-level @Transactional(readOnly=true) 가 이미 적용.
    public Optional<Cluster> findDomainById(String clusterName) {
        return clusterRepository.findById(clusterName).map(clusterMapper::toDomain);
    }

    @Override
    public List<Cluster> findAllDomain() {
        return clusterRepository.findAll().stream().map(clusterMapper::toDomain).toList();
    }

    @Override
    public org.springframework.data.domain.Page<Cluster> findAllDomain(
            org.springframework.data.domain.Pageable pageable) {
        return clusterRepository.findAll(pageable).map(clusterMapper::toDomain);
    }

    private static String toHex(String s) {
        if (s == null) return "<null>";
        byte[] bytes = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    /**
     * [ClusterServiceImpl] 클러스터 생성 함수
     *
     * @return 쿠버네티스 클러스터를 등록합니다.
     */
    @Transactional
    public HttpStatus createCluster(CreateClusterDto cluster) {
        // Timer.Sample 으로 전체 latency 측정. outcome counter 분기는 catch / return 별 적용.
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            HttpStatus result = createClusterInternal(cluster);
            createSuccess.increment();
            sample.stop(createTimer);
            return result;
        } catch (CustomException ce) {
            ErrorCode code = ce.getErrorCode();
            if (code == ErrorCode.DUPLICATE) createDuplicate.increment();
            else if (code == ErrorCode.INVALID_INPUT_VALUE) createInvalid.increment();
            else createError.increment();
            throw ce;
        } catch (RuntimeException e) {
            createError.increment();
            throw e;
        }
    }

    /** createCluster 의 원래 비즈니스 로직. metric wrap 은 {@link #createCluster}. */
    private HttpStatus createClusterInternal(CreateClusterDto cluster) {

        if (cluster == null) {
            throw new CustomException(ErrorCode.INVALID_INPUT_VALUE);
        }

        // 중복 체크: 같은 이름의 클러스터가 이미 존재하는지 확인
        if (clusterRepository.findById(cluster.getClusterName()).isPresent()) {
            throw new CustomException(ErrorCode.DUPLICATE);
        }

        // 모든 registered cluster 는 agent-led — body 에 K8s admin 자격
        // 받지 않음. 등록 직후 PENDING_AGENT 로 저장, cluster-agent 가 helm install 되어
        // backend 로 dial-in 하면 ACTIVE 전환. K8s API 직접 호출 흐름 (validateBase64Pem /
        // updateClusterVersionAndStatusAsync) 모두 제거.
        ClusterEntity clusterEntity = ClusterEntity.builder()
                .id(cluster.getClusterName())
                .description(cluster.getDescription())
                .status(com.aipaas.anycloud.domain.cluster.model.ClusterStatus.AGENT_PENDING)
                .version(null) // agent dial-in 시 backfill
                .clusterType(cluster.getClusterType())
                .clusterProvider(cluster.getClusterProvider())
                .provisioningType("IMPORTED")
                .provisioningStatus("READY")
                // null 이면 false (default 매핑). agent 가 노드 감지 시 backfill 로 갱신.
                .hasGpuNodes(Boolean.TRUE.equals(cluster.getHasGpuNodes()))
                .build();

        try {
            // 먼저 클러스터를 저장
            clusterRepository.save(clusterEntity);
            log.info(
                    "Cluster {} saved as AGENT_PENDING — cluster-agent helm install 후 dial-in 으로 ACTIVE 전환",
                    clusterEntity.getId());

        } catch (DataIntegrityViolationException e) {
            // Race window: line 126 의 findById 가 not-present 였더라도 동시 호출 thread 가 같은
            // cluster_name 으로 먼저 save 완료한 경우 DB unique constraint 위반. 일반적인
            // DATA_INTEGRITY (FK / NULL violation 등) 와 구분해 명확한 DUPLICATE 신호.
            String rootMsg =
                    e.getMostSpecificCause() != null ? e.getMostSpecificCause().getMessage() : e.getMessage();
            boolean isDuplicate = rootMsg != null
                    && (rootMsg.toLowerCase().contains("duplicate")
                            || rootMsg.toLowerCase().contains("unique"));
            if (isDuplicate) {
                log.warn("Cluster {} create race lost (concurrent duplicate): {}", cluster.getClusterName(), rootMsg);
                throw new CustomException(ErrorCode.DUPLICATE);
            }
            log.error("Cluster {} create failed with integrity violation: {}", cluster.getClusterName(), rootMsg, e);
            throw new CustomException(ErrorCode.DATA_INTEGRITY);
        }
        return HttpStatus.CREATED;
    }

    // validateBase64Pem / isBlank helpers 제거 — body 에 PEM 자격 없음.

    /**
     * [ClusterServiceImpl] 클러스터 업데이트 함수
     *
     * @param clusterName 업데이트할 클러스터 이름
     * @param updateDto   업데이트할 클러스터 정보
     * @return 업데이트 결과
     */
    @Transactional
    public HttpStatus updateCluster(String clusterName, UpdateClusterDto updateDto) {
        log.info("Starting cluster update for: {}", clusterName);

        // 1. 클러스터 존재 확인
        ClusterEntity clusterEntity =
                clusterRepository.findById(clusterName).orElseThrow(() -> new ClusterNotFoundException(clusterName));
        assertImportedCluster(clusterEntity);

        log.info(
                "Found cluster: {} (Status: {}, Version: {})",
                clusterEntity.getId(),
                clusterEntity.getStatus(),
                clusterEntity.getVersion());

        // 2. 부분 업데이트 수행
        updateClusterFields(clusterEntity, updateDto);
        log.info("Updated cluster fields for: {}", clusterName);

        // 3. 데이터베이스에 저장
        clusterRepository.save(clusterEntity);
        log.info("Successfully saved updated cluster: {}", clusterName);

        // K8s admin 자격이 더 이상 update body 에 없음 — KubernetesClient
        // 캐시 invalidate 도 무의미. connectivity test 도 cluster-agent dial-in 으로 대체.
        log.info("Cluster update completed successfully for: {}", clusterName);
        return HttpStatus.OK;
    }

    /**
     * 클러스터 필드 부분 업데이트 — non-null 값만 entity 에 반영.
     */
    private void updateClusterFields(ClusterEntity entity, UpdateClusterDto dto) {
        String id = entity.getId();
        // description / clusterType / clusterProvider 만 update 가능.
        // K8s admin 자격 (apiServer/serverCa/clientCa/Key/Token) + monitServerURL 은 제거 —
        // cluster-agent 가 source-of-truth.
        updateField(dto.getDescription(), entity::setDescription, "description", id);
        updateField(dto.getClusterType(), entity::setClusterType, "clusterType", id);
        updateField(dto.getClusterProvider(), entity::setClusterProvider, "clusterProvider", id);
    }

    /** {@code Common.updateIfNotNull} 호출 boilerplate 축약 helper. */
    private static <T> void updateField(
            T value, java.util.function.Consumer<T> setter, String field, String clusterId) {
        if (value != null) {
            setter.accept(value);
            log.debug("Updated {} for cluster: {}", field, clusterId);
        }
    }

    // isConnectionInfoChanged 제거 — update body 에 K8s 자격 없음.

    private void assertImportedCluster(ClusterEntity clusterEntity) {
        if ("PULUMI".equalsIgnoreCase(clusterEntity.getProvisioningType())) {
            throw new CustomException("VM 기반 클러스터는 /system/vm/clusters API로 관리해야 합니다.", ErrorCode.INVALID_INPUT_VALUE);
        }
    }

    /**
     * [ClusterServiceImpl] 클러스터 삭제 함수
     *
     * @return 쿠버네티스 클러스터를 삭제합니다.
     */
    @Transactional
    public HttpStatus deleteCluster(String clusterName) {
        ClusterEntity cluster = getClusterEntity(clusterName);
        assertImportedCluster(cluster);

        // Cascade cleanup — DB 에 명시적 FK 가 없으므로 application-level 정리.
        // cluster_agent: 동일 cluster_name 의 모든 agent row (rotate 중 multi-row 가능). 안 지우면
        // 같은 이름 cluster 재등록 시 stale agent 가 cluster_name match 로 살아남아 새 cert 발급
        // 경로에서 충돌. operation/audit_log 은 TTL sweeper 가 별도 정리 (resource_id 추적성 유지).
        long deletedAgents = clusterAgentRepository.deleteByClusterName(cluster.getId());
        if (deletedAgents > 0) {
            log.info("Cluster {} delete cascaded {} agent row(s)", cluster.getId(), deletedAgents);
        }

        clusterRepository.delete(cluster);
        bootstrapKubeClient.invalidate(cluster.getId());
        return HttpStatus.OK;
    }

    /**
     * [ClusterServiceImpl] 클러스터 연결 테스트 함수
     *
     * @param clusterName 테스트할 클러스터 이름
     * @return 연결 테스트 결과
     */
    // connectivity / status sync 로직은 ClusterConnectivityService 위임 (façade pattern).
    @Override
    public Boolean testClusterConnection(String clusterName) {
        return connectivityService.testClusterConnection(clusterName);
    }

    @Override
    @Transactional
    public HttpStatus refreshClusterStatus(String clusterName) {
        log.info("Starting forced status refresh for cluster: {}", clusterName);
        // ClusterNotFoundException + EntityNotFoundException 은 명시 propagate (404 보존),
        // 진짜 unexpected RuntimeException 만 500 으로 wrap.
        ClusterEntity cluster;
        try {
            cluster = clusterRepository
                    .findById(clusterName)
                    .orElseThrow(() -> new ClusterNotFoundException(clusterName));
        } catch (ClusterNotFoundException | EntityNotFoundException e) {
            log.warn("Cluster not found for status refresh: {}", clusterName);
            throw e;
        }
        try {
            connectivityService.updateClusterVersionAndStatus(cluster);
            log.info("Successfully refreshed status for cluster: {}", clusterName);
            return HttpStatus.OK;
        } catch (CustomException ce) {
            // 이미 분류된 application error — 그대로 propagate (404/403/503 등 보존).
            throw ce;
        } catch (RuntimeException e) {
            log.error("Failed to refresh status for cluster {}: {}", clusterName, e.getMessage(), e);
            throw new CustomException(
                    "Failed to refresh cluster status: " + e.getMessage(), ErrorCode.INTERNAL_SERVER_ERROR);
        }
    }

    @Override
    public void updateAllClusterStatuses() {
        connectivityService.updateAllClusterStatuses();
    }
}
