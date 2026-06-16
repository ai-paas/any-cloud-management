package com.aipaas.anycloud.domain.cluster;

import com.aipaas.anycloud.domain.cluster.model.ClusterStatus;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostPersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.io.Serial;
import java.io.Serializable;
import java.time.ZonedDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * <pre>
 * ClassName : ClusterEntity
 * Type : class
 * Description : Kubernetes Cluster와 관련된 Entity를 구성하고 있는 클래스입니다.
 * Related : ClusterRepository, ClusterServiceImpl
 * </pre>
 */
@Entity
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "cluster")
// api_server_*, server_ca, client_ca/key/token, monit_server_url 모두 제거
// cluster-agent 가 in-cluster 에서 모든 K8s API / monitoring 호출 대행. backend 는 admin 자격
// 보관 불필요. dial-in token + agent SA 권한이 source-of-truth.
@JsonPropertyOrder({
    "id",
    "description",
    "version",
    "cluster_type",
    "cluster_provider",
    "provisioning_type",
    "provisioning_status",
    "stack_name",
    "has_gpu_nodes",
    "created_at",
    "updated_at"
})
public class ClusterEntity implements Serializable {

    @Serial
    private static final long serialVersionUID = -8133064720847622575L;

    @Id
    @NotNull
    @Size(max = 45)
    @Column(name = "id", nullable = false, length = 45)
    private String id;

    @Size(max = 255)
    @Column(name = "description")
    private String description;

    /**
     * Cluster lifecycle status. — type-safe enum 으로 마이그레이션 완료
     * ({@link ClusterStatus}). DB 컬럼은 VARCHAR(45) 유지 — {@code @Enumerated(STRING)} 가
     * enum.name() ↔ string 변환. {@link #transitionStatus} helper 사용 권장 (graph 검증).
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 45)
    private ClusterStatus status;

    @Size(max = 45)
    @Column(name = "version", length = 45)
    private String version;

    // api_server_url / api_server_ip / server_ca / client_ca / client_key /
    // client_token / monit_server_url 모두 제거. backend 가 cluster K8s API 직접 호출 안 함 —
    // cluster-agent dial-in 으로 대체. monitoring URL 은 agent 가 in-cluster service discover.

    @Size(max = 100)
    @Column(name = "cluster_type", nullable = false, length = 100)
    private String clusterType;

    @Size(max = 100)
    @Column(name = "cluster_provider", nullable = false, length = 100)
    private String clusterProvider;

    @Size(max = 50)
    @Column(name = "provisioning_type", length = 50)
    private String provisioningType;

    @Size(max = 50)
    @Column(name = "provisioning_status", length = 50)
    private String provisioningStatus;

    /**
     * GPU 노드 보유 여부. VM 프로비저닝 시 GPU flavor 선택되면 true. true 인 경우
     * cluster-observability 의 MonitoringAutoInstaller 가 dcgm-exporter 도 자동 설치.
     */
    @Builder.Default
    @Column(name = "has_gpu_nodes", nullable = false)
    private Boolean hasGpuNodes = false;

    @Size(max = 100)
    @Column(name = "stack_name", length = 100)
    private String stackName;

    /**
     * Cluster label (K8s-style). 운영자가 free-form 으로 지정 — anycloud 가 의미 부여하는 것은
     * 다음 사용처들:
     * <ul>
     *   <li>{@code anycloud.io/tier=prod|stg|dev} — RBAC starter 의 tieredRoleRefs 매칭</li>
     *   <li>{@code anycloud.io/region=us-west-1} — fleet view 의 grouping (선택)</li>
     *   <li>{@code anycloud.io/customer=acme} — multi-tenant 환경의 ownership (선택)</li>
     * </ul>
     *
     * <p>JSON column — Map&lt;String, String&gt; 형태로 직렬화. 빈 Map 으로 default.
     */
    @Builder.Default
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(name = "tags", columnDefinition = "json")
    private java.util.Map<String, String> tags = new java.util.LinkedHashMap<>();

    @Builder.Default
    @Column(
            name = "created_at",
            nullable = false,
            updatable = false,
            columnDefinition = "TIMESTAMP DEFAULT CURRENT_TIMESTAMP")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm", locale = "ko_KR", timezone = "Asia/Seoul")
    private ZonedDateTime createdAt = ZonedDateTime.now();

    @Builder.Default
    @Column(
            name = "updated_at",
            nullable = false,
            columnDefinition = "TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm", locale = "ko_KR", timezone = "Asia/Seoul")
    private ZonedDateTime updatedAt = ZonedDateTime.now();

    @PostPersist
    protected void onCreate() {
        this.createdAt = ZonedDateTime.now();
        this.updatedAt = ZonedDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = ZonedDateTime.now();
    }

    // ============= State transition helper =============

    private static final org.slf4j.Logger STATE_LOG =
            org.slf4j.LoggerFactory.getLogger(ClusterEntity.class.getName() + ".state");

    /**
     * Validated status transition. {@link ClusterStatus#canTransitionTo} 로 graph 검증 후 status 갱신.
     * status field 가 enum 으로 마이그레이션 완료, 직접 setter 대신 본 helper 권장.
     * 호출 측이 type-safe + invalid transition 감지 가능.
     *
     * <p>호출 예: {@code cluster.transitionStatus(ClusterStatus.ACTIVE, "health-check.ok")}
     *
     * @param next 새 상태 (enum)
     * @param reason 호출 위치 / 사유 (log 용도).
     */
    public void transitionStatus(ClusterStatus next, String reason) {
        ClusterStatus current = this.status != null ? this.status : ClusterStatus.UNKNOWN;
        if (!current.canTransitionTo(next)) {
            STATE_LOG.warn(
                    "cluster {} status transition VIOLATION: {} → {} (reason={}) — observation mode, applied",
                    this.id,
                    current,
                    next,
                    reason);
        } else {
            STATE_LOG.debug("cluster {} status: {} → {} (reason={})", this.id, current, next, reason);
        }
        this.status = next;
    }

    /**
     * VM(Pulumi) 프로비저닝 cluster 여부. {@code provisioning_type == "PULUMI"} 단일 진실 — VM 분기
     * (admin kubeconfig SA 자동 생성 등) 는 모두 본 메서드 사용. registered/manual 은 "IMPORTED".
     */
    public boolean isVmProvisioned() {
        return "PULUMI".equalsIgnoreCase(provisioningType);
    }
}
