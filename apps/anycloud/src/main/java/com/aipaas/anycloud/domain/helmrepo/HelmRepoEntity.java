package com.aipaas.anycloud.domain.helmrepo;

import com.aipaas.anycloud.domain.helmrepo.model.HelmRepoSource;
import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;
import lombok.*;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * <pre>
 * ClassName : HelmRepoEntity
 * Type : class
 * Description : HelmRepository와 관련된 Entity를 구성하고 있는 클래스입니다.
 * Related : HelmRepoRepository, HelmRepoServiceImpl
 * </pre>
 */
@Entity
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "helm_repo")
public class HelmRepoEntity implements Serializable {
    @Serial
    private static final long serialVersionUID = 3860595808137796449L;

    @Id
    @Size(max = 36)
    @Column(name = "id", nullable = false, length = 36)
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Size(max = 100)
    @NotNull
    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Size(max = 100)
    @NotNull
    @Column(name = "url", nullable = false, length = 100)
    private String url;

    @Size(max = 100)
    @Column(name = "username", length = 100)
    private String username;

    @Size(max = 100)
    @Column(name = "password", length = 100)
    private String password;

    //	@Lob
    //	@Column(name = "cert_file")
    //	private String certFile;

    //	@Lob
    //	@Column(name = "key_file")
    //	private String keyFile;

    @Lob
    @Column(name = "ca_file")
    private String caFile;

    @NotNull
    @ColumnDefault("0")
    @Builder.Default
    @Column(name = "insecure_skip_tls_verify", nullable = false)
    private Boolean insecureSkipTlsVerify = false;

    // auto_allowlist column 폐기.
    // Chart 제한 원하면 ConfigMap (allowed_charts) 에 직접 명시.

    /**
     * Hybrid helm-repo. INTERNAL | EXTERNAL.
     * Default EXTERNAL — 대부분의 chart 는 public repo. 동작 무관 (URL 로만 fetch).
     * Mirror 처럼 외부 chart 의 internal cache 는 사용 시점에서 INTERNAL 과 동일 — tags 로 출처 추적.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 20)
    @ColumnDefault("'EXTERNAL'")
    @Builder.Default
    private HelmRepoSource source = HelmRepoSource.EXTERNAL;

    /** Free-form comma-separated tags. UI filter 용도. */
    @Size(max = 255)
    @Column(name = "tags", length = 255)
    private String tags;

    @Column(name = "created_at", nullable = false, updatable = false)
    @CreationTimestamp
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    @UpdateTimestamp
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updatedAt;
}
