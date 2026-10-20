package com.aipaas.anycloud.domain.credential.api;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

/** 자격증명 입력 화면의 칸 하나. */
@Builder
@Schema(description = "프로바이더별 자격증명 입력 필드")
public record CredentialFieldSchema(
        @Schema(description = "저장될 키 이름", example = "AWS_ACCESS_KEY_ID") String key,
        @Schema(description = "화면에 보일 이름", example = "액세스 키 ID") String label,
        @Schema(description = "없으면 프로비저닝이 실패하는 값", example = "true") boolean required,
        @Schema(description = "값을 화면에 드러내지 않아야 하는 값", example = "false") boolean secret,
        @Schema(description = "여러 줄 — 키 본문이나 JSON", example = "false") boolean multiline,
        @Schema(description = "무엇을 넣어야 하는지", example = "IAM 사용자의 액세스 키") String description,
        /* 값 형식 예시. 그대로 복사해 사용하는 경우가 있어 실제 값처럼 보이면 안 됨. */
        @Schema(description = "입력 예시", example = "AKIAIOSFODNN7EXAMPLE") String placeholder,
        /*
         * 동일 group 의 필드는 하나만 입력. 양쪽을 필수로 요구하면 입력 불가.
         *
         * <p>현재 사용하는 CSP 없음. 화면이 그룹 렌더링을 지원하므로 계약만 유지.
         */
        @Schema(description = "택일 묶음 이름. 같은 값끼리 하나만 채우면 된다", example = "auth") String group) {}
