package com.aipaas.anycloud.domain.agent;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;
import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Cluster Agent registration_token (JWT) 서명용 HMAC 키 영구 저장.
 *
 * <p>backend 재시작마다 random 키가 생성되어 기존 등록된 cluster-agent 의 JWT 가 invalid 가 되는
 * critical bug 해소용 (V21). instance 간 + 재시작 간 동일한 키를 공유하기 위한 single source.
 *
 * <p>Rotation: 새 키는 새 row insert + 이전 row {@code active=false} 마킹. resolver 는 {@code
 * active=true} 중 가장 최근 (id DESC) 을 primary 로 선택.
 */
@Entity
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "agent_signing_key")
public class AgentSigningKeyEntity implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /** Stable UUID — rotation history 추적용 (e.g. agent log 가 어떤 key 로 sign 됐는지). */
    @NotNull
    @Column(name = "key_id", nullable = false, length = 36, unique = true)
    private String keyId;

    /** HMAC raw bytes (>=32 for HS256). VARBINARY(128) — 256bit (32B) ~ 1024bit (128B) 범위 커버. */
    @NotNull
    @Column(name = "key_bytes", nullable = false, length = 128)
    private byte[] keyBytes;

    @NotNull
    @Builder.Default
    @Column(name = "algorithm", nullable = false, length = 20)
    private String algorithm = "HS256";

    @NotNull
    @Builder.Default
    @Column(name = "active", nullable = false)
    private Boolean active = true;

    @NotNull
    @Builder.Default
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "deactivated_at")
    private LocalDateTime deactivatedAt;
}
