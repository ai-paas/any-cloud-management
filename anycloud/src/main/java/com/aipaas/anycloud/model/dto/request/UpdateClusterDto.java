package com.aipaas.anycloud.model.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.io.Serial;
import java.io.Serializable;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * UpdateClusterDto.
 *
 * @author taking
 * @description 클러스터 연동(수정) 요청 관련 DTO 클래스
 * @since 2025-09-18
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UpdateClusterDto implements Serializable {

	@Serial
	private static final long serialVersionUID = -2290543268215032683L;

	@Schema(description = "클러스터 설명")
	@Size(max = 500, message = "설명은 500자를 초과할 수 없습니다")
	private String description;

	@Schema(description = "클러스터 유형 (Public, Private)")
	// @Pattern(regexp = "^(Public|Private)$", message = "클러스터 유형은 Public 또는
	// Private이어야 합니다")
	private String clusterType;

	@Schema(description = "클러스터 공급자 (AWS, GCP, Azure, OpenStack 등)")
	@Size(max = 50, message = "클러스터 공급자명은 50자를 초과할 수 없습니다")
	private String clusterProvider;

	@Schema(description = "클러스터 API 서버 URL")
	// @Pattern(regexp = "^https?://.*", message = "올바른 URL 형식이어야 합니다")
	private String apiServerUrl;

	@Schema(description = "클러스터 API 서버 IP")
	// @Pattern(regexp = "^(?:[0-9]{1,3}\\.){3}[0-9]{1,3}$", message = "올바른 IP 주소
	// 형식이어야 합니다")
	private String apiServerIp;

	@Schema(description = "클러스터 ServerCA (Base64 인코딩된 인증서)")
	private String serverCa;

	@Schema(description = "클러스터 ClientCA (Base64 인코딩된 인증서)")
	private String clientCa;

	@Schema(description = "클러스터 ClientKey (Base64 인코딩된 키)")
	private String clientKey;

	@Schema(description = "클러스터 모니터링 서버 URL")
	// @Pattern(regexp = "^https?://.*", message = "올바른 URL 형식이어야 합니다")
	private String monitServerUrl;
}