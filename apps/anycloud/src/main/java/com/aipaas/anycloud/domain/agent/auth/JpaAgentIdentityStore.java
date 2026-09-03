package com.aipaas.anycloud.domain.agent.auth;

import com.aipaas.anycloud.domain.agent.ClusterAgentEntity;
import com.aipaas.anycloud.domain.agent.ClusterAgentRepository;
import com.aipaas.anycloud.domain.agent.model.ClusterAgentStatus;
import io.aipaas.cluster.agent.core.AgentIdentity;
import io.aipaas.cluster.agent.core.AgentIdentityStore;
import io.aipaas.cluster.agent.core.AgentStatus;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cluster Agent Starter 의 {@link AgentIdentityStore} 를 anycloud 의 JPA repository 로 구현하는
 * adapter.
 *
 * <p>mTLS 제거. cert 관련 필드 매핑 코드 모두 폐기. Bearer 단일 인증.
 *
 * <p>변환:
 * <ul>
 *   <li>{@link ClusterAgentEntity} ↔ {@link AgentIdentity} (starter record)</li>
 *   <li>{@link ClusterAgentStatus} ↔ {@link AgentStatus}</li>
 *   <li>{@link LocalDateTime} ↔ {@link Instant} (시스템 기본 ZoneId 기준)</li>
 * </ul>
 *
 * <p>Starter 가 본 bean 을 자동 inject — 별도 wiring 불필요.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JpaAgentIdentityStore implements AgentIdentityStore {

    private final ClusterAgentRepository repository;

    @Override
    @Transactional(readOnly = true)
    public Optional<AgentIdentity> findByIdentityTokenHash(String tokenHash) {
        return repository.findByIdentityTokenHash(tokenHash).map(JpaAgentIdentityStore::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<AgentIdentity> findByClusterName(String clusterName) {
        return repository.findByClusterName(clusterName).stream()
                .map(JpaAgentIdentityStore::toDomain)
                .toList();
    }

    @Override
    @Transactional
    public AgentIdentity save(AgentIdentity identity) {
        ClusterAgentEntity entity = repository.findById(identity.agentId()).orElseGet(() -> ClusterAgentEntity.builder()
                .agentId(identity.agentId())
                .build());
        applyToEntity(identity, entity);
        ClusterAgentEntity saved = repository.save(entity);
        return toDomain(saved);
    }

    @Override
    @Transactional
    public boolean updateStatus(String agentId, AgentStatus status, String errorMessage) {
        Optional<ClusterAgentEntity> opt = repository.findById(agentId);
        if (opt.isEmpty()) {
            return false;
        }
        ClusterAgentEntity entity = opt.get();
        entity.setStatus(toJpaStatus(status));
        entity.setLastError(errorMessage);
        repository.save(entity);
        return true;
    }

    @Override
    @Transactional
    public AgentIdentity rotateToken(String agentId, String newIdentityTokenHash, Instant newExpiresAt) {
        Optional<ClusterAgentEntity> opt = repository.findById(agentId);
        if (opt.isEmpty()) {
            return null;
        }
        ClusterAgentEntity e = opt.get();
        e.setIdentityTokenHash(newIdentityTokenHash);
        e.setExpiresAt(toLocal(newExpiresAt));
        // revoke 풀기 — rotation 으로 다시 살아나는 의미.
        e.setRevokedAt(null);
        ClusterAgentEntity saved = repository.save(e);
        return toDomain(saved);
    }

    @Override
    @Transactional
    public int updateLastSeen(String clusterName, Instant lastSeenAt, Instant lastK8sApiOkAt) {
        List<ClusterAgentEntity> active = repository.findByClusterNameAndStatus(clusterName, ClusterAgentStatus.ACTIVE);
        int updated = 0;
        for (ClusterAgentEntity e : active) {
            if (lastSeenAt != null) {
                e.setLastSeenAt(toLocal(lastSeenAt));
            }
            if (lastK8sApiOkAt != null) {
                e.setLastK8sApiOkAt(toLocal(lastK8sApiOkAt));
            }
            repository.save(e);
            updated++;
        }
        return updated;
    }

    // ============ 변환 helpers ============

    static AgentIdentity toDomain(ClusterAgentEntity e) {
        return new AgentIdentity(
                e.getAgentId(),
                e.getClusterName(),
                e.getAgentInstanceId(),
                e.getIdentityTokenHash(),
                toDomainStatus(e.getStatus()),
                toInstant(e.getLastSeenAt()),
                toInstant(e.getLastK8sApiOkAt()),
                toInstant(e.getExpiresAt()),
                toInstant(e.getRevokedAt()),
                e.getLastError());
    }

    private static void applyToEntity(AgentIdentity src, ClusterAgentEntity tgt) {
        tgt.setAgentId(src.agentId());
        tgt.setClusterName(src.clusterName());
        tgt.setAgentInstanceId(src.agentInstanceId());
        tgt.setIdentityTokenHash(src.identityTokenHash());
        tgt.setStatus(toJpaStatus(src.status()));
        tgt.setLastSeenAt(toLocal(src.lastSeenAt()));
        tgt.setLastK8sApiOkAt(toLocal(src.lastK8sApiOkAt()));
        tgt.setExpiresAt(toLocal(src.expiresAt()));
        tgt.setRevokedAt(toLocal(src.revokedAt()));
        tgt.setLastError(src.lastError());
    }

    static AgentStatus toDomainStatus(ClusterAgentStatus s) {
        if (s == null) {
            return null;
        }
        return switch (s) {
            case REGISTERING -> AgentStatus.REGISTERING;
            case REGISTERED -> AgentStatus.REGISTERED;
            case ACTIVE -> AgentStatus.ACTIVE;
            case DEGRADED -> AgentStatus.DEGRADED;
            case FAILED -> AgentStatus.FAILED;
            case REVOKED -> AgentStatus.REVOKED;
        };
    }

    static ClusterAgentStatus toJpaStatus(AgentStatus s) {
        if (s == null) {
            return null;
        }
        return switch (s) {
            case REGISTERING -> ClusterAgentStatus.REGISTERING;
            case REGISTERED -> ClusterAgentStatus.REGISTERED;
            case ACTIVE -> ClusterAgentStatus.ACTIVE;
            case DEGRADED -> ClusterAgentStatus.DEGRADED;
            case FAILED -> ClusterAgentStatus.FAILED;
            case REVOKED -> ClusterAgentStatus.REVOKED;
        };
    }

    private static Instant toInstant(LocalDateTime ldt) {
        return ldt == null ? null : ldt.atZone(ZoneId.systemDefault()).toInstant();
    }

    private static LocalDateTime toLocal(Instant i) {
        return i == null ? null : LocalDateTime.ofInstant(i, ZoneId.systemDefault());
    }
}
