package com.aipaas.anycloud.domain.helmrepo.api.request;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import java.io.Serial;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 헬름저장소(추가) 요청 DTO.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CreateHelmRepoRequest {
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

    /**
     * Hybrid helm-repo source. 미지정 시 EXTERNAL (default).
     *
     * <p>INTERNAL: 사용자가 운영하는 chart 저장소 (ChartMuseum / Harbor / OCI). 외부 chart 의 internal
     * mirror 도 endpoint 가 internal 이면 INTERNAL 로 분류.<br>
     * EXTERNAL: public chart 저장소 (helm.sh / github pages).
     */
    @Schema(
            description = "저장소 종류 (INTERNAL/EXTERNAL) — default EXTERNAL",
            example = "EXTERNAL",
            defaultValue = "EXTERNAL")
    private com.aipaas.anycloud.domain.helmrepo.model.HelmRepoSource source;

    /** Free-form comma-separated tags. UI filter 용도. */
    @Schema(description = "tags (comma-separated) — UI filter", example = "monitoring,default")
    private String tags;
}
