package com.aipaas.anycloud.domain.agent.bootstrap;

import com.aipaas.anycloud.domain.addon.AddonOrchestrator;
import com.aipaas.anycloud.domain.agent.ClusterAgentEntity;
import com.aipaas.anycloud.domain.agent.ClusterAgentRepository;
import com.aipaas.anycloud.domain.agent.model.ClusterAgentStatus;
import com.aipaas.anycloud.domain.cluster.ClusterEntity;
import com.aipaas.anycloud.domain.cluster.ClusterRepository;
import io.aipaas.cluster.agent.identity.AgentJwtProperties;
import io.aipaas.cluster.agent.identity.JwtRegistrationTokenService;
import io.aipaas.cluster.agent.identity.JwtRegistrationTokenService.IssuedToken;
import io.aipaas.cluster.agent.identity.JwtRegistrationTokenService.RegistrationClaims;
import io.aipaas.cluster.agent.identity.TokenHasher;
import io.aipaas.cluster.agent.v1.AgentIdentity;
import io.aipaas.cluster.agent.v1.ClusterIdentity;
import io.aipaas.cluster.agent.v1.NetworkInfo;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cluster Agent 등록 비즈니스 로직.
 *
 * <ul>
 *   <li>{@link #issueRegistrationToken}: REST → 단기 JWT 발급 (cluster 등록 사전 필요).</li>
 *   <li>{@link #register}: gRPC bootstrap → JWT 검증 + cluster_agent upsert + identity_token 발급.</li>
 *   <li>{@link #rotateIdentityToken}: 살아있는 token → 신규 token 교체.</li>
 * </ul>
 *
 * <p>mTLS 제거. Rancher 와 동일한 bearer-over-TLS 모델로 회귀. 인증 path 가
 * 단일 (identity_token), CSR/cert renewal 코드 모두 폐기. 60일 TTL 의 opaque token 이 K8s Secret 에
 * 영구 저장 (pod 재시작 안정성). 운영 단순함 우선.
 *
 * <p>synchronous flow (DB transaction 완료 후 응답). 향후 비동기 saga 도입 시 RabbitMQ.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentBootstrapServiceImpl implements AgentBootstrapService {

    /** opaque identity token 길이 (bytes → hex). 256-bit 면 brute-force 불가능. */
    private static final int IDENTITY_TOKEN_BYTES = 32;

    private final JwtRegistrationTokenService jwtService;
    private final AgentJwtProperties properties;
    private final ClusterRepository clusterRepository;
    private final ClusterAgentRepository clusterAgentRepository;
    // ACTIVE 전환 시 helm_repositories 자동 push.
    private final com.aipaas.anycloud.domain.agent.policy.ClusterPolicyBootstrapper clusterPolicyBootstrapper;
    // ACTIVE 전환 시 PENDING addon 자동 enqueue. ObjectProvider 로 lazy/optional
    // 주입 — addon-workflow.enabled=false 인 환경 (인프라 미설치) 에서도 bootstrap path 정상 동작.
    private final org.springframework.beans.factory.ObjectProvider<AddonOrchestrator> addonOrchestratorProvider;
    // GPU cluster (hasGpuNodes=true) ACTIVE 시 nvidia-gpu-operator 자동 enroll.
    private final org.springframework.beans.factory.ObjectProvider<com.aipaas.anycloud.domain.addon.AddonService>
            addonServiceProvider;

    /** installMode = HELM_BOOTSTRAP / MANUAL / API_MANAGED. cluster 미등록 시 {@link ClusterNotRegisteredException}. */
    @Transactional(readOnly = true)
    @Override
    @com.aipaas.anycloud.domain.audit.Audited(
            action = "agent.issueRegistrationToken",
            resourceType = "cluster",
            resourceId = "#clusterId",
            summary = "'installMode=' + (#installMode ?: 'MANUAL')")
    public IssuedToken issueRegistrationToken(String clusterId, String installMode) {
        if (!clusterRepository.findById(clusterId).isPresent()) {
            throw new ClusterNotRegisteredException("Cluster not found: " + clusterId);
        }
        String normalized = (installMode == null || installMode.isBlank()) ? "MANUAL" : installMode.toUpperCase();
        log.info("Issuing registration_token cluster_id={} install_mode={}", clusterId, normalized);
        return jwtService.issue(clusterId, normalized);
    }

    /**
     * JWT 검증 + (cluster_id, agent_instance_id) upsert + identity_token (32B opaque hex) 발급.
     *
     * <p>인증 모델: 본 register 가 발급한 token 을 agent 가 K8s Secret 에 저장 후 모든 후속 gRPC
     * 호출의 Authorization Bearer header 에 부착. 60 일 TTL (configurable via
     * {@code cluster-agent.identity.ttl-days}). 만료 임박 시 rotateIdentityToken 으로 갱신.
     */
    @Transactional
    @Override
    @com.aipaas.anycloud.domain.audit.Audited(
            action = "agent.register",
            resourceType = "clusterAgent",
            resourceId = "#result?.clusterId()",
            summary =
                    "'instanceId=' + #agentIdentity?.agentInstanceId() " + "+ ', status=' + #result?.status()?.name()")
    public RegistrationResult register(
            String registrationToken,
            ClusterIdentity clusterIdentity,
            AgentIdentity agentIdentity,
            NetworkInfo network) {
        RegistrationClaims claims = jwtService.verifyAndConsume(registrationToken);

        String clusterId = claims.clusterId();
        // Cluster 가 여전히 존재하는지 (JWT 발급 후 삭제될 수 있음).
        ClusterEntity clusterEntity = clusterRepository.findById(clusterId).orElseThrow(() -> {
            log.warn("Register rejected: cluster vanished after token issued (cluster_id={})", clusterId);
            return new ClusterNotRegisteredException("Cluster not found: " + clusterId);
        });

        // Opaque identity token — 32 bytes hex.
        String identityToken = generateOpaqueToken();
        String identityHash = TokenHasher.sha256Hex(identityToken);
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expiresAt = now.plusDays(properties.identity().ttlDays());

        String agentInstanceId =
                agentIdentity == null || agentIdentity.getAgentInstanceId().isBlank()
                        ? UUID.randomUUID().toString()
                        : agentIdentity.getAgentInstanceId();

        // 같은 cluster + instance_id 면 update (Agent 재시작 case), 아니면 insert (HA replica).
        ClusterAgentEntity existing = clusterAgentRepository
                .findByClusterNameAndAgentInstanceId(clusterId, agentInstanceId)
                .orElse(null);

        ClusterAgentEntity entity = existing == null
                ? ClusterAgentEntity.builder()
                        .agentId(UUID.randomUUID().toString())
                        .build()
                : existing;

        entity.setClusterName(clusterId);
        entity.setAgentInstanceId(agentInstanceId);
        entity.setK8sClusterUid(emptyAsNull(safeString(clusterIdentity, ClusterIdentity::getK8SClusterUid)));
        entity.setIdentityTokenHash(identityHash);
        entity.setStatus(ClusterAgentStatus.REGISTERED);
        entity.setAgentVersion(safeString(agentIdentity, AgentIdentity::getVersion));
        entity.setDistribution(safeString(clusterIdentity, ClusterIdentity::getDistribution));
        entity.setK8sVersion(safeString(clusterIdentity, ClusterIdentity::getVersion));
        entity.setEndpoint(safeString(clusterIdentity, ClusterIdentity::getEndpoint));
        entity.setPublicIp(safeString(network, NetworkInfo::getPublicIp));
        entity.setPrivateIp(safeString(network, NetworkInfo::getPrivateIp));
        entity.setPodCidr(safeString(clusterIdentity, ClusterIdentity::getPodCidr));
        entity.setServiceCidr(safeString(clusterIdentity, ClusterIdentity::getServiceCidr));
        entity.setRegisteredAt(now);
        entity.setLastSeenAt(now);
        entity.setExpiresAt(expiresAt);
        entity.setRevokedAt(null); // 재등록 시 이전 revoke 해제.
        entity.setLastError(null);

        clusterAgentRepository.save(entity);

        // AGENT_PENDING (Helm bootstrap 경로) placeholder ClusterEntity → agent 가 보고한 메타로 backfill.
        backfillClusterFromAgent(clusterEntity, clusterIdentity);

        log.info("Agent registered cluster_id={} instance_id={} expires_at={}", clusterId, agentInstanceId, expiresAt);
        return new RegistrationResult(clusterId, identityToken, expiresAt, ClusterAgentStatus.REGISTERED);
    }

    /**
     * Agent 가 보고한 ClusterIdentity 로 ClusterEntity 의 빈 메타 backfill.
     * <ul>
     *   <li>apiServerUrl / serverCa : null/blank 일 때만 채움 (사용자 입력 보존).</li>
     *   <li>version : 항상 agent 가 보고한 최신 값으로 갱신 (K8s 가 업그레이드되면 따라감).</li>
     *   <li>status : AGENT_PENDING 이었으면 ACTIVE 로 전환. 그 외 상태는 유지.</li>
     * </ul>
     */
    private void backfillClusterFromAgent(ClusterEntity cluster, ClusterIdentity identity) {
        if (identity == null) return;
        boolean dirty = false;
        // apiServerUrl / serverCa 컬럼 제거 — agent 가 in-cluster 에서 K8s API
        // 자체에 SA token 으로 접근하므로 backend 에 저장할 필요 없음. endpoint/CA 정보는 무시.
        String version = emptyAsNull(identity.getVersion());
        // 값 변경 시에만 dirty. 매 register 마다 동일 버전 write 하면
        // @UpdateTimestamp 가 updated_at 을 갱신하고 cluster_state_history audit row 가 spurious 양산됨.
        if (version != null && !version.equals(cluster.getVersion())) {
            cluster.setVersion(version);
            dirty = true;
        }
        boolean justActivated = false;
        if (com.aipaas.anycloud.domain.cluster.model.ClusterStatus.AGENT_PENDING == cluster.getStatus()) {
            cluster.transitionStatus(com.aipaas.anycloud.domain.cluster.model.ClusterStatus.ACTIVE, "agent.bootstrap");
            dirty = true;
            justActivated = true;
        }
        if (dirty) {
            clusterRepository.save(cluster);
            log.info(
                    "ClusterEntity backfilled from agent (cluster_id={}, status={}, version={})",
                    cluster.getId(),
                    cluster.getStatus(),
                    cluster.getVersion());
        }
        // (+) — agent stream connect 시점 마다 helm_repositories sync.
        // PENDING_AGENT → ACTIVE 첫 전환 시 push (welcome 시점)
        // 이미 ACTIVE 인 cluster 의 agent 재접속 시에도 push (reconnect 회복).
        //        backend 재기동 사이 발생한 repo CRUD 가 누락된 agent 도 reconnect 시점에 회복.
        // @Async 라 본 경로 막지 않음. 매 reconnect 마다 호출되지만 멱등 (agent SyncRepositoriesWithCleanup).
        clusterPolicyBootstrapper.pushOnActive(cluster.getId());

        // GPU cluster 면 nvidia-gpu-operator 자동 enroll — driver / nvidia-container-toolkit /
        // device-plugin / GFD 의 K8s native 관리. 운영자 manual create 호출 없이 cluster ACTIVE 즉시
        // install. AddonService.create 가 catalog default 채우며, namespace+releaseName 중복 시
        // silent skip (idempotent — 매 agent reconnect 마다 호출되어도 안전).
        if (justActivated && Boolean.TRUE.equals(cluster.getHasGpuNodes())) {
            try {
                com.aipaas.anycloud.domain.addon.AddonService addonService = addonServiceProvider.getIfAvailable();
                if (addonService != null) {
                    com.aipaas.anycloud.domain.addon.model.AddonSpec spec =
                            new com.aipaas.anycloud.domain.addon.model.AddonSpec(
                                    com.aipaas.anycloud.domain.addon.model.AddonType.GENERIC,
                                    "nvidia-gpu-operator",
                                    null,
                                    null,
                                    null,
                                    null,
                                    null,
                                    null,
                                    null,
                                    true);
                    addonService.create(cluster.getId(), spec);
                    log.info("GPU operator auto-enrolled for cluster_id={}", cluster.getId());
                }
            } catch (Exception e) {
                log.warn(
                        "GPU operator auto-enroll failed (non-blocking) cluster={}: {}", cluster.getId(), e.toString());
            }
        }

        // justActivated 또는 reconnect 시점 모두에서 PENDING/FAILED 인 addon 들 enqueue.
        // 신규 cluster 의 cluster-create-time 선택 addon, 또는 운영 중 추가된 addon (backfill) 둘 다 동일.
        // SUCCEEDED 는 자동 skip (orchestrator idempotency). addon-workflow.enabled=false 면 bean 부재.
        try {
            AddonOrchestrator orch = addonOrchestratorProvider.getIfAvailable();
            if (orch != null) {
                orch.enqueuePendingForCluster(cluster.getId());
            }
        } catch (Exception e) {
            log.warn("AddonOrchestrator enqueue failed (non-blocking) cluster={}: {}", cluster.getId(), e.toString());
        }
    }

    private static String generateOpaqueToken() {
        byte[] random = new byte[IDENTITY_TOKEN_BYTES];
        new SecureRandom().nextBytes(random);
        return HexFormat.of().formatHex(random);
    }

    /**
     * Identity token rotation — 살아있는 token hash → 새 token 발급, DB 교체. revoked/expired 면 거부.
     * @param requestingInstanceId 진단용 (HA 시 어느 instance 가 rotate 했는지 log).
     */
    @Transactional
    @Override
    @com.aipaas.anycloud.domain.audit.Audited(
            action = "agent.rotateIdentityToken",
            resourceType = "clusterAgent",
            resourceId = "#requestingInstanceId",
            summary = "'newExpiresAt=' + #result?.expiresAt()")
    public RotationResult rotateIdentityToken(String currentTokenHash, String requestingInstanceId) {
        var existing = clusterAgentRepository
                .findByIdentityTokenHash(currentTokenHash)
                .orElseThrow(() -> new RotationDeniedException("current identity_token not found"));
        if (existing.getRevokedAt() != null) {
            throw new RotationDeniedException("agent revoked at " + existing.getRevokedAt());
        }
        if (existing.getExpiresAt() != null
                && existing.getExpiresAt().isBefore(LocalDateTime.now().minusSeconds(1))) {
            // 이미 만료 — rotation 불가. 새 registration_token 으로 재등록 필요.
            throw new RotationDeniedException("identity_token already expired at " + existing.getExpiresAt());
        }

        String newToken = generateOpaqueToken();
        String newHash = TokenHasher.sha256Hex(newToken);
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime newExpiresAt = now.plusDays(properties.identity().ttlDays());

        existing.setIdentityTokenHash(newHash);
        existing.setExpiresAt(newExpiresAt);
        existing.setRevokedAt(null);
        clusterAgentRepository.save(existing);

        log.info(
                "Identity token rotated cluster_id={} instance_id={} (requested by {}) new_expires_at={}",
                existing.getClusterName(),
                existing.getAgentInstanceId(),
                requestingInstanceId == null ? "" : requestingInstanceId,
                newExpiresAt);
        return new RotationResult(newToken, newExpiresAt);
    }

    private static <T> String safeString(T obj, java.util.function.Function<T, String> getter) {
        if (obj == null) return null;
        String v = getter.apply(obj);
        return v == null || v.isBlank() ? null : v;
    }

    private static String emptyAsNull(String v) {
        return (v == null || v.isBlank()) ? null : v;
    }
}
