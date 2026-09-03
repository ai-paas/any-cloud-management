package com.aipaas.anycloud.domain.vmoptions.api;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.Builder;

/**
 * 한 provider 의 {@code spec.config} 키 1개를 기술하는 schema entry.
 *
 * <p>클라이언트가 form generation 또는 validation 미리보기에 사용. 사용자에게 보여줄 모든
 * config 옵션을 한 곳에서 enumerate 가능 — Swagger description 으로는 발견 어려운
 * 30+ 키들을 명시적으로 노출.
 */
@Builder
@Schema(description = "Pulumi config map 의 키 schema entry")
public record ProviderConfigKey(
        @Schema(description = "전체 키 이름 (anycloud-k8s: prefix 포함)", example = "anycloud-k8s:masterCount") String key,
        @Schema(
                        description = "값 타입 — 모든 값은 string 으로 전달되지만 의미상 type",
                        example = "integer",
                        allowableValues = {"string", "integer", "boolean", "cidr", "enum"})
                String type,
        @Schema(description = "필수 여부 — true 면 누락 시 400", example = "false") boolean required,
        @Schema(description = "기본값 (applyDefaults 가 putIfAbsent). null 이면 default 없음", example = "1")
                String defaultValue,
        @Schema(
                        description = "사용자에게 보여줄 설명",
                        example = "Control-plane 노드 수. 1=single, 3/5/7=HA. odd-only (etcd quorum).")
                String description,
        @Schema(
                        description = "허용값 (enum/boolean) 이거나 정수 범위 (\"1..7\"). null 이면 제약 없음.",
                        example = "[\"1\", \"3\", \"5\", \"7\"]")
                List<String> allowedValues) {}
