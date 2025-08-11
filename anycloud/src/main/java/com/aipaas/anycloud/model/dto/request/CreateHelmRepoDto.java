package com.aipaas.anycloud.model.dto.request;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;

/**
 * CreateHelmRepoDto.
 *
 * @author yby654
 * @description 헬름저장소(추가) 요청 관련 DTO 클래스
 * @since 2025-08-1
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CreateHelmRepoDto {
	@Serial
	private static final long serialVersionUID = 144537395066610019L;

	@NotBlank
	@Schema(description = "헬름 저장소 명")
	private String name;

	@NotBlank
	@Schema(description = "헬름 저장소 Url")
	private String url;

	@Schema(description = "헬름 저장소 사용자 아이디")
	private String username;

	@Schema(description = "헬름 저장소 사용자 비밀번호")
	private String password;

	@Schema(description = "헬름 저장소 INSECURE 사용 여부")
	@JsonProperty(value = "insecureSkipTLSVerify")
	private boolean insecureSkipTlsVerify;

	@Schema(description = "헬름 저장소 CA 정보")
	private String caFile;
}
