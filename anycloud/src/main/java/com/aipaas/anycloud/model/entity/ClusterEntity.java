package com.aipaas.anycloud.model.entity;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
@Table(name = "cluster", schema = "aipaas")
@JsonPropertyOrder({ "id", "description", "version", "api_server_url", "api_server_ip", "server_ca",
		"client_ca", "client_key", "monit_server_url", "cluster_type", "cluster_provider", "created_at", "updated_at" })
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

	@Size(max = 45)
	@Column(name = "status", length = 45)
	private String status;

	@Size(max = 45)
	@Column(name = "version", length = 45)
	private String version;

	@NotNull
	@Size(max = 100)
	@Column(name = "api_server_url", nullable = false, length = 100)
	private String apiServerUrl;

	@Size(max = 45)
	@Column(name = "api_server_ip", length = 45)
	private String apiServerIp;

	@Column(name = "server_ca", nullable = false, columnDefinition = "MEDIUMTEXT")
	private String serverCa;

	@Column(name = "client_ca", columnDefinition = "MEDIUMTEXT")
	private String clientCa;

	@Column(name = "client_key", columnDefinition = "MEDIUMTEXT")
	private String clientKey;

	@Column(name = "client_token", columnDefinition = "MEDIUMTEXT")
	private String clientToken;

	@Size(max = 100)
	@Column(name = "monit_server_url", nullable = false, length = 100)
	private String monitServerUrl;

	@Size(max = 100)
	@Column(name = "cluster_type", nullable = false, length = 100)
	private String clusterType;

	@Size(max = 100)
	@Column(name = "cluster_provider", nullable = false, length = 100)
	private String clusterProvider;

	@Builder.Default
	@Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "TIMESTAMP DEFAULT CURRENT_TIMESTAMP")
	@JsonFormat(pattern = "yyyy-MM-dd HH:mm", locale = "ko_KR", timezone = "Asia/Seoul")
	private ZonedDateTime createdAt = ZonedDateTime.now();

	@Builder.Default
	@Column(name = "updated_at", nullable = false, columnDefinition = "TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP")
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
}
