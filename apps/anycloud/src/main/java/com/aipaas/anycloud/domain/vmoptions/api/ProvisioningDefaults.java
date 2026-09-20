package com.aipaas.anycloud.domain.vmoptions.api;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;
import lombok.Builder;

/**
 * 그 CSP 에서 지금 바로 통과하는 생성 요청 한 벌.
 *
 * <p>값을 화면에 박아 두면 스펙이나 이미지가 갈릴 때마다 어긋나고, 그 사실을 생성 실패로야
 * 알게 된다. 계정에서 실제로 고를 수 있는 값을 조회해 조립한다.
 */
@Builder
@Schema(description = "CSP 별로 지금 통과하는 생성 기본값")
public record ProvisioningDefaults(
        @Schema(description = "CSP 식별자", example = "AWS") String provider,
        @Schema(description = "화면 표시명", example = "AWS") String displayName,
        @Schema(description = "이 값으로 지금 만들 수 있는지", example = "true") boolean ready,
        @Schema(description = "만들 수 없는 이유. ready=true 면 null", example = "ap-tokyo-1 에 자리가 없습니다") String blockedReason,
        @Schema(description = "쓸 자격증명 ID") String credentialId,
        @Schema(description = "자격증명 이름", example = "aws-e2e-02") String credentialName,
        @Schema(description = "리전", example = "ap-northeast-2") String region,
        @Schema(description = "master 인스턴스 타입", example = "t3.large") String masterInstanceType,
        @Schema(description = "worker 인스턴스 타입", example = "t3.large") String workerInstanceType,
        @Schema(description = "OS 이미지. 필수가 아닌 CSP 는 null") String osImage,
        @Schema(description = "CSP 고유 설정") Map<String, String> providerSpec) {}
