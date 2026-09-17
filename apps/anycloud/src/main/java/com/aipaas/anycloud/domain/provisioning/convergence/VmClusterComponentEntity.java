package com.aipaas.anycloud.domain.provisioning.convergence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.io.Serial;
import java.io.Serializable;
import java.time.ZonedDateTime;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** VM cluster 별 컴포넌트의 desired 대비 observed 상태와 재시도 회계. */
@Entity
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
// ddl-auto 로 스키마를 만드는 환경(통합 테스트)과 Flyway 가 같은 제약을 갖도록 명시한다.
// 엔티티에만 없으면 관측이 매 주기 중복 행을 만들어도 아무도 막지 못한다.
@Table(
        name = "vm_cluster_component",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_vm_cluster_component",
                        columnNames = {"vm_cluster_id", "component_type"}))
public class VmClusterComponentEntity implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Id
    // "vmcc-" + UUID = 41자. Flyway 의 varchar(64) 와 반드시 같아야 한다 — ddl-auto 로 스키마를
    // 만드는 환경에서 값이 작으면 insert 가 항상 깨진다.
    @Column(name = "id", nullable = false, length = 64)
    private String id;

    /** FK vm_cluster.id. 그쪽이 varchar(36) 이라 길이를 맞춘다. */
    @Column(name = "vm_cluster_id", nullable = false, length = 36)
    private String vmClusterId;

    @Enumerated(EnumType.STRING)
    @Column(name = "component_type", nullable = false, length = 32)
    private ComponentType componentType;

    @Enumerated(EnumType.STRING)
    @Column(name = "requirement", nullable = false, length = 16)
    private Requirement requirement;

    @Enumerated(EnumType.STRING)
    @Column(name = "health", nullable = false, length = 16)
    private ComponentHealth health;

    @Column(name = "attempts", nullable = false)
    private Integer attempts;

    @Column(name = "next_attempt_at")
    private ZonedDateTime nextAttemptAt;

    @Column(name = "last_probed_at")
    private ZonedDateTime lastProbedAt;

    @Column(name = "last_applied_at")
    private ZonedDateTime lastAppliedAt;

    /** CSP stderr 는 수 KB 다. varchar 로 자르면 진단이 불가능해진다. */
    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private ZonedDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private ZonedDateTime updatedAt;

    @PrePersist
    void onCreate() {
        if (id == null) {
            id = "vmcc-" + UUID.randomUUID();
        }
        if (health == null) {
            health = ComponentHealth.UNKNOWN;
        }
        if (attempts == null) {
            attempts = 0;
        }
        ZonedDateTime now = ZonedDateTime.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = ZonedDateTime.now();
    }
}
