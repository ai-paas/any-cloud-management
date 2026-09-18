package com.aipaas.anycloud.domain.credential;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

@Entity
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(
        name = "csp_credential",
        // 이름만으로는 구분이 안 돼 프로비저닝에서 어느 것을 고른 건지 알 수 없었다.
        // provider 가 다르면 같은 이름을 허용한다 — "dev" 를 AWS 와 GCP 에 각각 쓰는 건 자연스럽다.
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_csp_credential_provider_name",
                        columnNames = {"provider", "name"}))
public class CspCredentialEntity implements Serializable {

    @Serial
    private static final long serialVersionUID = -1585548767573509146L;

    @Id
    @Size(max = 36)
    @Column(name = "id", nullable = false, length = 36)
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @NotNull
    @Size(max = 100)
    @Column(name = "provider", nullable = false, length = 100)
    private String provider;

    @NotNull
    @Size(max = 100)
    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Size(max = 255)
    /**
     * 마지막 확인 결과. 화면 상태로만 두면 새로고침하면 사라지고 사용자마다 각자 확인해야 한다.
     * null 이면 한 번도 확인한 적이 없다는 뜻이다 — "정상" 과 구분되어야 한다.
     */
    @Column(name = "health_status", length = 20)
    private String healthStatus;

    @Column(name = "health_kind", length = 40)
    private String healthKind;

    @Column(name = "health_detail", length = 1000)
    private String healthDetail;

    @Column(name = "health_checked_at")
    private LocalDateTime healthCheckedAt;

    /** 리전이 없는 프로바이더는 0 이다. 한 번도 확인하지 않았으면 null. */
    @Column(name = "health_checked_regions")
    private Integer healthCheckedRegions;

    @Column(name = "description")
    private String description;

    @Lob
    @Column(name = "encrypted_payload", columnDefinition = "LONGTEXT")
    private String encryptedPayload;

    @Lob
    @Column(name = "credential_keys", columnDefinition = "TEXT")
    private String credentialKeys;

    @Column(name = "active", nullable = false)
    @Builder.Default
    private Boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    @CreationTimestamp
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    @UpdateTimestamp
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updatedAt;
}
