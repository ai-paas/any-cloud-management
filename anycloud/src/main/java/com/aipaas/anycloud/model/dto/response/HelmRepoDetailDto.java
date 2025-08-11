package com.aipaas.anycloud.model.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * HelmRepoListDto.
 *
 * @author yby654
 * @description 리스트 형 헬름저장소 목록 반환 DTO 클래스
 * @since 2025-08-11
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class HelmRepoDetailDto {
	@Schema(description = "헬름 저장소 명")
	private String name;

	@Schema(description = "헬름 저장소 Url")
	private String url;

	@Schema(description = "헬름 저장소 INSECURE 사용 여부")
	@JsonProperty(value = "insecureSkipTLSVerify")
	private boolean insecureSkipTlsVerify;

	@Schema(description = "사용자 아이디")
	private String username;

	@Schema(description = "비밀번호")
	private String password;

	@Schema(description = "cert file")
	private String certFile;

	@Schema(description = "key file")
	private String keyFile;

	@Schema(description = "ca file")
	private String caFile;

	@Schema(description = "생성 일시")
	private LocalDateTime createdAt;

	@Schema(description = "수정 일시")
	private LocalDateTime updatedAt;
}

