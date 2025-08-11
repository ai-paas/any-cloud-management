package com.aipaas.anycloud.model.entity;


import io.fabric8.generator.annotation.Size;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

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
@Table(name = "helm_repo", schema = "aipaas")
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
	@Column(name = "insecure_skip_tls_verify", nullable = false)
	private Boolean insecureSkipTlsVerify = false;

	@Column(name = "created_at", nullable = false, updatable = false)
	@CreationTimestamp
	private LocalDateTime createdAt;

	@Column(name = "updated_at", nullable = false)
	@UpdateTimestamp
	private LocalDateTime updatedAt;

}
