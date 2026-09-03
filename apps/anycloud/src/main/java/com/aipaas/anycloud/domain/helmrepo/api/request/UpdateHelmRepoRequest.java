package com.aipaas.anycloud.domain.helmrepo.api.request;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.io.Serial;
import java.io.Serializable;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Helm repository PATCH 요청 body.
 *
 * <p>Partial update — 모든 필드 nullable. 보내지 않은 필드는 현재 값 유지. primitive boolean 은
 * Wrapper {@link Boolean} 으로 null 가능.
 *
 * <p><b>name 변경 미지원</b>:
 * <ul>
 *   <li>이유 1: name 은 URL identity (PATH variable). REST 의미상 resource identity 변경은 별도 op</li>
 *   <li>이유 2: agent allowlist 의 chart rule 식별자가 {@code <repo-name>/<chart>} 형식 — name 변경은
 *       모든 cluster 의 allowlist 와 unsync</li>
 *   <li>이유 3: 기존 helm release 들이 본 repo 이름으로 chart 를 reference</li>
 * </ul>
 * 따라서 본 DTO 에 {@code name} 필드 없음. 이름 변경 필요하면 <b>DELETE + POST 패턴</b> 사용:
 * <ol>
 *   <li>해당 repo 의 모든 release uninstall (operator 책임)</li>
 *   <li>DELETE /v1/helm-repos/{old-name}</li>
 *   <li>POST /v1/helm-repos with new name</li>
 *   <li>release 재배포</li>
 * </ol>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Helm repository 부분 갱신 요청. name 변경 미지원 — DELETE + POST 패턴 사용.")
public class UpdateHelmRepoRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "Repository URL")
    private String url;

    @Schema(description = "username")
    private String username;

    @Schema(description = "password")
    private String password;

    @Schema(description = "Insecure TLS 사용 여부")
    @JsonProperty(value = "insecureSkipTLSVerify")
    private Boolean insecureSkipTlsVerify;

    @Schema(description = "CA 정보")
    private String caFile;

    /** Hybrid helm-repo source. null = 변경 없음. */
    @Schema(description = "저장소 종류 (INTERNAL/EXTERNAL)", example = "EXTERNAL")
    private com.aipaas.anycloud.domain.helmrepo.model.HelmRepoSource source;

    @Schema(description = "tags (comma-separated)")
    private String tags;
}
