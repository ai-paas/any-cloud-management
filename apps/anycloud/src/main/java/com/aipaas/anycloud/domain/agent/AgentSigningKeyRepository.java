package com.aipaas.anycloud.domain.agent;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * {@link AgentSigningKeyEntity} JPA repository. {@link JpaSigningKeyResolver} 가 사용.
 */
public interface AgentSigningKeyRepository extends JpaRepository<AgentSigningKeyEntity, Long> {

    /**
     * 현재 활성 key 중 가장 최근 (id DESC) row. rotation 후에도 정상 동작:
     * 새 row insert 시 새 key 가 자동 primary 로 선택됨.
     */
    Optional<AgentSigningKeyEntity> findFirstByActiveTrueOrderByIdDesc();
}
