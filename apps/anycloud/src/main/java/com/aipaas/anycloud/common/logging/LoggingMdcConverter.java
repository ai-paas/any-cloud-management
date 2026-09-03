package com.aipaas.anycloud.common.logging;

import ch.qos.logback.classic.pattern.ClassicConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;
import java.util.Map;

/**
 * logback %anycloudMdc 변환 — workflow 컨텍스트 키들을 한 토큰으로 합쳐 출력.
 * <p>
 * 값이 모두 비어 있으면 빈 문자열을 반환해 일반 요청 로그(컨텍스트 없는 경우)에 잡음을 남기지
 * 않는다. 값이 하나라도 있으면 {@code [cluster=… msg=… step=… retry=N] } 형태로 출력하고
 * 끝에 공백 한 칸을 둬 뒤따르는 logger 이름과 시각적으로 분리.
 */
public class LoggingMdcConverter extends ClassicConverter {

    /** MDC 키 → 출력 약어. 등록 순서대로 출력된다. */
    private static final Map<String, String> ABBREV = new java.util.LinkedHashMap<>();

    static {
        // 요청 단위 컨텍스트가 가장 먼저 표시되도록 (한 요청을 grep 으로 추적할 때 유리).
        ABBREV.put(LoggingMdc.REQUEST_ID, "req");
        ABBREV.put(LoggingMdc.CLIENT_IP, "ip");
        ABBREV.put(LoggingMdc.CLUSTER_NAME, "cluster");
        ABBREV.put(LoggingMdc.MESSAGE_ID, "msg");
        ABBREV.put(LoggingMdc.STEP, "step");
        ABBREV.put(LoggingMdc.RETRY_COUNT, "retry");
        ABBREV.put(LoggingMdc.PROVISIONING_ID, "prov");
    }

    @Override
    public String convert(ILoggingEvent event) {
        Map<String, String> mdc = event.getMDCPropertyMap();
        if (mdc == null || mdc.isEmpty()) {
            return "";
        }
        StringBuilder out = null;
        for (var entry : ABBREV.entrySet()) {
            String value = mdc.get(entry.getKey());
            if (value == null || value.isBlank()) {
                continue;
            }
            if (out == null) {
                out = new StringBuilder("[");
            } else {
                out.append(' ');
            }
            // messageId 는 UUID 라 너무 길어 앞 8자만 보여준다(전체는 메시지 본문 또는 DB 에서 조회).
            if (LoggingMdc.MESSAGE_ID.equals(entry.getKey()) && value.length() > 8) {
                value = value.substring(0, 8);
            }
            out.append(entry.getValue()).append('=').append(value);
        }
        return out == null ? "" : out.append("] ").toString();
    }
}
