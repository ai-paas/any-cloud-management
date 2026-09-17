package com.aipaas.anycloud.domain.provisioning.api.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** 강제 삭제 결과. destroy 를 돌리지 않았으므로 무엇이 남을 수 있는지 함께 알린다. */
@Schema(description = "강제 삭제 결과 — 기록만 삭제")
public record ForceDeleteResponse(
        @Schema(description = "지운 기록 수", example = "2") int removedRecords,
        @Schema(
                        description = "클라우드에 남아 있을 수 있는 Pulumi 스택. 비어 있으면 만들어진 자원이 없었다.",
                        example = "[\"anycloud-OpenStack-dev-demo\"]")
                List<String> orphanedStacks,
        @Schema(description = "사용자가 직접 확인해야 할 내용") String warning) {

    public static ForceDeleteResponse of(int removedRecords, List<String> orphanedStacks) {
        String warning = orphanedStacks.isEmpty()
                ? "만들어진 자원이 없어 클라우드에 남는 것이 없습니다."
                : "destroy 를 돌리지 않았습니다. 아래 스택의 자원이 CSP 에 남아 있을 수 있으니 직접 확인해주세요.";
        return new ForceDeleteResponse(removedRecords, orphanedStacks, warning);
    }
}
