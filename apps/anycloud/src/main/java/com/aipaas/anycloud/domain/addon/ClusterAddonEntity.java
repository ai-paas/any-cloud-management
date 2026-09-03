package com.aipaas.anycloud.domain.addon;

import com.aipaas.anycloud.domain.addon.model.AddonState;
import com.aipaas.anycloud.domain.addon.model.AddonType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.io.Serial;
import java.io.Serializable;
import java.time.ZonedDateTime;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Cluster 별 helm addon 설치 spec + lifecycle state.  *
 * <p>frontend 가 cluster 생성 시 checkbox 로 선택한 addon (monitoring/velero 등) 의 per-cluster
 * spec 과 비동기 install state 를 추적. RabbitMQ background install path 의 source-of-truth.
 *
 * <p>설계 결정:
 * <ul>
 *   <li>cluster 별 다른 spec 허용 — chart_version/repo/values 는 row 마다 column.</li>
 *   <li>state 가 cluster state 와 독립 — cluster=ACTIVE/addon=FAILED 가능.</li>
 *   <li>catalog (Option B) 와 custom override 둘 다 — catalogId nullable.</li>
 * </ul>
 *
 * @see com.aipaas.anycloud.domain.addon.AddonState
 * @see com.aipaas.anycloud.domain.addon.AddonType
 */
@Entity
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "cluster_addon")
public class ClusterAddonEntity implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    /** FK cluster.id. CASCADE delete — cluster 제거 시 addon row 도 정리. */
    @Column(name = "cluster_id", nullable = false, length = 45)
    private String clusterId;

    @Enumerated(EnumType.STRING)
    @Column(name = "addon_type", nullable = false, length = 48)
    private AddonType addonType;

    /** Option B catalog ref. null = pure custom spec (catalog 미사용). */
    @Column(name = "catalog_id", length = 64)
    private String catalogId;

    @Column(name = "release_name", nullable = false, length = 128)
    private String releaseName;

    @Column(name = "namespace", nullable = false, length = 128)
    private String namespace;

    @Column(name = "chart_repo", nullable = false, length = 128)
    private String chartRepo;

    @Column(name = "chart_name", nullable = false, length = 128)
    private String chartName;

    @Column(name = "chart_version", nullable = false, length = 32)
    private String chartVersion;

    /** agent alias resolve 우회용 명시 URL. null 이면 agent fallback. */
    @Column(name = "repo_url", length = 512)
    private String repoUrl;

    /** Optional values override (JSON 또는 YAML string). */
    @Column(name = "values_yaml", columnDefinition = "MEDIUMTEXT")
    private String valuesYaml;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 24)
    private AddonState state;

    /** 최신 OperationEntity ref — SSE 연결용. install 시작 시 set, retry 시 갱신. */
    @Column(name = "last_operation_id", length = 36)
    private String lastOperationId;

    /** FAILED 시점의 에러 메시지. */
    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    /** 누적 install 시도 횟수 — retry backoff 산정 / metric 용. */
    @Column(name = "attempts", nullable = false)
    private Integer attempts;

    /** soft disable — false 면 자동 enqueue 제외. row 제거 없이 toggle. */
    @Column(name = "enabled", nullable = false)
    private Boolean enabled;

    @Column(name = "created_at", nullable = false, updatable = false)
    private ZonedDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private ZonedDateTime updatedAt;

    @PrePersist
    void onCreate() {
        if (id == null) {
            id = "addon-" + UUID.randomUUID();
        }
        if (state == null) {
            state = AddonState.PENDING;
        }
        if (attempts == null) {
            attempts = 0;
        }
        if (enabled == null) {
            enabled = Boolean.TRUE;
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
