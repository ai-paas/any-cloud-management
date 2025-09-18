package com.aipaas.anycloud.util;

import java.util.function.Consumer;
import java.util.regex.Pattern;
import org.slf4j.Logger;

public class Common {

	public static boolean isValidUUID(String uuidString) {
		// UUID 형식을 검증하는 정규 표현식
		String uuidRegex = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$";
		// 입력된 문자열이 UUID 형식과 일치하는지 확인
		return Pattern.matches(uuidRegex, uuidString);
	}

	/**
	 * 값이 null이 아닌 경우에만 setter를 호출하고 로그를 출력하는 유틸리티 메서드
	 *
	 * @param newValue   새로운 값
	 * @param setter     setter 메서드 참조
	 * @param logger     로거 인스턴스
	 * @param logMessage 로그 메시지
	 * @param args       로그 메시지의 인수들
	 * @param <T>        값의 타입
	 */
	public static <T> void updateIfNotNull(T newValue, Consumer<T> setter, Logger logger, String logMessage,
			Object... args) {
		if (newValue != null) {
			setter.accept(newValue);
			logger.debug(logMessage, args);
		}
	}

}
