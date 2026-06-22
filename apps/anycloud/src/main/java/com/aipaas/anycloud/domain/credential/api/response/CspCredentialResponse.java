package com.aipaas.anycloud.domain.credential.api.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "CSP 자격증명 응답 DTO")
public class CspCredentialResponse {

    @Schema(description = "자격증명 ID", example = "cred-001")
    private String id;

    @Schema(description = "클라우드 제공자", example = "AWS")
    private String provider;

    @Schema(description = "자격증명 이름", example = "aws-dev-credential")
    private String name;

    @Schema(description = "자격증명 설명", example = "AWS development account")
    private String description;

    @Schema(description = "활성 여부", example = "true")
    private Boolean active;

    @Schema(description = "저장된 credential key 목록", example = "[\"AWS_ACCESS_KEY_ID\",\"AWS_SECRET_ACCESS_KEY\"]")
    private List<String> credentialKeys;

    @Schema(description = "생성 시각", example = "2026-04-03T14:00:00")
    private LocalDateTime createdAt;

    @Schema(description = "수정 시각", example = "2026-04-03T14:05:00")
    private LocalDateTime updatedAt;
}
