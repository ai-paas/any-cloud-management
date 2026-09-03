package com.aipaas.anycloud.domain.helmrepo.api.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 헬름저장소 목록 반환 DTO.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class HelmRepoListResponse {
    @Schema(description = "헬름 저장소 명", example = "chart-museum-external")
    private String name;

    @Schema(description = "헬름 저장소 Url", example = "http://192.168.190.148:5080")
    private String url;

    @Schema(description = "헬름 저장소 INSECURE 사용 여부", example = "true")
    @JsonProperty(value = "insecureSkipTLSVerify")
    private boolean insecureSkipTlsVerify;

    @Schema(description = "생성 일시", example = "2026-04-03T13:45:00")
    private LocalDateTime createdAt;

    /** Hybrid helm-repo source. */
    @Schema(description = "저장소 종류", example = "EXTERNAL")
    private com.aipaas.anycloud.domain.helmrepo.model.HelmRepoSource source;

    @Schema(description = "tags", example = "monitoring,default")
    private String tags;
}
