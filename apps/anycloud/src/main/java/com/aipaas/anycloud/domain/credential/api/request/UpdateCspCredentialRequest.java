package com.aipaas.anycloud.domain.credential.api.request;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 자격증명 수정 요청.
 *
 * <p>이름과 프로바이더는 없다 — vm_cluster 가 프로비저닝 당시 이름을 기록으로 들고 있고,
 * 프로바이더를 바꾸는 것은 새로 등록하는 것과 다르지 않다.
 */
@Getter
@Setter
@NoArgsConstructor
@Schema(description = "자격증명 수정 요청")
public class UpdateCspCredentialRequest {

    @Schema(description = "설명. null 이면 그대로 둔다", example = "AWS 운영 계정")
    private String description;

    @Schema(description = "교체할 값 전체. 비어 있으면 값은 건드리지 않는다. 일부만 보내면 나머지는 사라진다")
    private Map<String, String> credentials;
}
