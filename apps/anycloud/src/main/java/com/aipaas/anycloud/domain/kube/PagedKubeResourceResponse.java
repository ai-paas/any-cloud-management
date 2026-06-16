package com.aipaas.anycloud.domain.kube;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * K8s server-side pagination 응답. {@code metadata.continue} 가 다음 호출의 {@code continue}
 * 파라미터로 전달되어 chunked 순회.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PagedKubeResourceResponse {

    @Schema(description = "클러스터 이름")
    private String clusterName;

    @Schema(description = "네임스페이스 (요청한 값, all-namespaces 면 null)")
    private String namespace;

    @Schema(description = "리소스 종류 (pods 등)")
    private String resourceType;

    @Schema(description = "이번 페이지의 리소스 list (JSON 배열)")
    private JsonNode items;

    @Schema(
            description = "다음 페이지 호출용 continue token. null/빈 문자열이면 마지막 페이지.",
            example = "eyJ2IjoibWV0YS5rOHMuaW8vdjEi...")
    private String continueToken;

    @Schema(description = "이번 페이지 항목 수")
    private int returnedItemCount;

    /**
     * Circuit fallback 으로 empty list 가 반환된 경우 true. 정상 응답 (실제 0건) 과 구분하기 위한
     * UX 시그널 — UI 는 이 값을 보고 "결과 없음" vs "agent / 권한 문제로 조회 불가" 명확히 알림.
     *
     * <p>일반 응답에서는 {@code null} (JSON omit). Degraded 인 경우만 {@code true}.
     */
    @Schema(description = "true면 agent/circuit 문제로 부분 가용성 (items 가 empty 인 이유). 정상은 null/false.", example = "true")
    private Boolean degraded;

    /**
     * Degraded 시 reason code (UI 가 i18n 키로 매핑 가능).
     * <ul>
     *   <li>AGENT_INACTIVE — cluster agent 가 backend 에 connect 안 됨 (no active session)</li>
     *   <li>NAMESPACE_NOT_ALLOWED — agent 의 namespace allowlist 미허용</li>
     *   <li>AGENT_ERROR — agent 가 다른 reason 으로 fail (RPC timeout / internal)</li>
     *   <li>CIRCUIT_OPEN — circuit breaker OPEN (반복 실패로 일시 차단)</li>
     * </ul>
     */
    @Schema(description = "degraded reason code", example = "AGENT_INACTIVE")
    private String degradedReason;

    /** Human-readable detail — 운영자 진단 / 사용자 메시지 후보. */
    @Schema(description = "degraded detail message", example = "Cluster agent not connected — install agent first")
    private String degradedMessage;
}
