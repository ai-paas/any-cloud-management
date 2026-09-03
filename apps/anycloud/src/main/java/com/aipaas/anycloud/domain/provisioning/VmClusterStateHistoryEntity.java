package com.aipaas.anycloud.domain.provisioning;

import com.aipaas.anycloud.domain.provisioning.model.VmClusterStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * VmCluster status 변경 audit row — state transition audit history.
 *
 * <p>모든 {@code VmClusterEntity.transitionTo(...)} 호출이 한 row 발행. operator 가 "이 cluster 가
 * 어떤 step 들을 거쳤나" 시간순 query 가능. {@code valid=false} 면 state machine graph 가 invalid
 * 라고 판정한 transition (observation mode 에서 그대로 진행됨) — 회귀 추적용.
 */
@Entity
@Table(name = "vm_cluster_state_history")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VmClusterStateHistoryEntity {

    @Id
    @Column(name = "id", length = 36, nullable = false)
    private String id;

    @Column(name = "cluster_name", length = 128, nullable = false)
    private String clusterName;

    @Column(name = "vm_cluster_id", length = 36)
    private String vmClusterId;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_state", length = 32)
    private VmClusterStatus fromState;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_state", length = 32, nullable = false)
    private VmClusterStatus toState;

    @Column(name = "reason", length = 128)
    private String reason;

    @Column(name = "principal", length = 128)
    private String principal;

    @Column(name = "request_id", length = 64)
    private String requestId;

    @Column(name = "valid", nullable = false)
    @Builder.Default
    private Boolean valid = true;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
