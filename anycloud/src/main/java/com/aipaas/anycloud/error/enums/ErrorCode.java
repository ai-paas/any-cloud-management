package com.aipaas.anycloud.error.enums;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Getter;
import lombok.ToString;

/**
 * <pre>
 * ClassName : ErrorCode
 * Type : enum
 * Description : 에러 코드, 에러 메시지를 포함하고 있는 enum입니다.
 * Related : ErrorResponse
 * </pre>
 */
@Getter
@ToString
@JsonFormat(shape = JsonFormat.Shape.OBJECT)
public enum ErrorCode {

	// Common
	INTERNAL_SERVER_ERROR(500, "서버에 문제가 발생했습니다."),
	RUNTIME_EXCEPTION(400, "잘못된 요청입니다."),
	INVALID_INPUT_VALUE(400, "유효하지 않는 입력 값입니다."),
	ENTITY_NOT_FOUND(400, "데이터를 찾을 수 없습니다."),
	METHOD_NOT_ALLOWED(405, "허용되지 않는 메소드입니다."),
	INVALID_TYPE_VALUE(400, "유효하지 않은 유형 값입니다."),
	NOT_FOUND(404, "찾을 수 없습니다."),
	FORBIDDEN(403, "접근 권한이 없어 거부되었습니다."),
	ACCESS_DENIED_EXCEPTION(401, "인증 정보가 유효하지 않습니다."),
	DUPLICATE(409, "중복된 데이터가 있습니다."),
	NO_BODY(400, "입력된 바디 또는 파라미터가 없습니다."),
	DATA_INTEGRITY(403, "데이터가 정상적으로 처리되지 못했습니다."),

	// Cluster related errors
	CLUSTER_NOT_FOUND(404, "클러스터를 찾을 수 없습니다."),
	CLUSTER_CONNECTION_FAILED(503, "클러스터 연결에 실패했습니다."),
	CLUSTER_INVALID_CONFIG(400, "클러스터 설정이 유효하지 않습니다.");

	private final int status;
	private final String message;

	ErrorCode(final int status, final String message) {
		this.status = status;
		this.message = message;
	}
}
