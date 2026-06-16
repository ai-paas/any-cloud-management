package com.aipaas.anycloud.domain.agent;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

/**
 * JWT registration_token jti 의 1회 사용 기록. Redis SET NX 의 DB 기반 대체.
 *
 * <p>{@link com.aipaas.anycloud.domain.agent.bootstrap.JpaIdempotencyStore} 가 INSERT IGNORE 패턴으로 사용.
 */
@Entity
@Table(name = "bootstrap_jti_used")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BootstrapJtiEntity {

    @Id
    @Column(name = "jti", length = 64, nullable = false)
    private String jti;

    @CreationTimestamp
    @Column(name = "used_at", nullable = false, updatable = false)
    private LocalDateTime usedAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;
}
