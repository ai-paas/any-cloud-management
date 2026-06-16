package com.aipaas.anycloud.domain.credential.api.request;

import com.aipaas.anycloud.domain.credential.model.CspCredentialSourceType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import java.io.Serial;
import java.io.Serializable;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "CSP 자격증명 생성 DTO")
public class CreateCspCredentialRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = -7403856981577158038L;

    @NotBlank
    @Schema(description = "클라우드 제공자", example = "AWS")
    private String provider;

    @NotBlank
    @Schema(description = "자격증명 이름", example = "aws-dev-credential")
    private String name;

    @Schema(description = "자격증명 설명", example = "AWS development account")
    private String description;

    @Schema(description = "자격증명 저장 방식", example = "MANUAL")
    private CspCredentialSourceType sourceType;

    @Schema(
            description = "MANUAL 방식일 때 저장할 credential key/value",
            example = "{\"AWS_ACCESS_KEY_ID\":\"AKIA...\",\"AWS_SECRET_ACCESS_KEY\":\"***\"}")
    private Map<String, String> credentials;
}
