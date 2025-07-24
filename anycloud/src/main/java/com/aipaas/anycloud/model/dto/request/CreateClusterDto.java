package com.aipaas.anycloud.model.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import java.io.Serial;
import java.io.Serializable;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * CreateClusterDto.
 *
 * @author taking
 * @description 클러스터 연동(추가) 요청 관련 DTO 클래스
 * @since 2025-07-23
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreateClusterDto implements Serializable {

	@Serial
	private static final long serialVersionUID = -2290543268215032683L;

	@NotBlank
	@Schema(description = "클러스터 공급자")
	private String clusterProvider;

	@NotBlank
	@Schema(description = "클러스터 아이디(클러스터 명)")
	private String clusterName;

	@Schema(description = "클러스터 설명")
	private String description;

	@NotBlank
	@Schema(description = "클러스터 API 서버 IP")
	private String apiServerIp;

	@NotBlank
	@Schema(description = "클러스터 API 서버 URL")
	private String apiServerUrl;

	@NotBlank
	@Schema(description = "클러스터 ServerCA")
	private String serverCA;

	@NotBlank
	@Schema(description = "클러스터 ClientCA")
	private String clientCA;

	@NotBlank
	@Schema(description = "클러스터 ClientKey")
	private String clientKey;
}
