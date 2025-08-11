package com.aipaas.anycloud.model.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;


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
public class HelmRepoListDto {
	@Schema(description = "헬름 저장소 명")
	private String name;

	@Schema(description = "헬름 저장소 Url")
	private String url;

	@Schema(description = "헬름 저장소 INSECURE 사용 여부")
	@JsonProperty(value = "insecureSkipTLSVerify")
	private boolean insecureSkipTlsVerify;

	@Schema(description = "생성 일시")
	private LocalDateTime createdAt;
}

