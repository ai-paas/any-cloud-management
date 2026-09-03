package com.aipaas.anycloud.common.logging;

import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.MDC;

/**
 * MDC 컨텍스트 키 정의 + try-with-resources 헬퍼.
 * <p>
 * 비동기 / 메시지 컨슈머 경계에서 {@link MDC} 의 값을 채워두면 logback 패턴이
 * 자동으로 모든 로그 줄에 cluster/messageId/step 등을 표시. 호출자는
 * {@link #scope} 로 try-with-resources 블록을 열어 자동 클리어를 보장.
 *
 * <pre>{@code
 * try (var ignored = LoggingMdc.scope(Map.of(
 *         LoggingMdc.CLUSTER_NAME, message.getClusterName(),
 *         LoggingMdc.MESSAGE_ID, message.getMessageId(),
 *         LoggingMdc.STEP, message.getStep().name()))) {
 *     handle(message);
 * }
 * }</pre>
 */
public final class LoggingMdc {

    // 요청 컨텍스트 (Servlet filter 가 채움).
    public static final String REQUEST_ID = "requestId";
    public static final String CLIENT_IP = "clientIp";

    // Workflow / async 컨텍스트.
    public static final String CLUSTER_NAME = "clusterName";
    public static final String MESSAGE_ID = "messageId";
    public static final String STEP = "step";
    public static final String RETRY_COUNT = "retryCount";
    public static final String PROVISIONING_ID = "provisioningId";

    private LoggingMdc() {}

    /**
     * 주어진 key→value 들을 MDC 에 put, close 시 원복 (비어있던 키는 제거).
     * null/blank value 는 skip, 그 외는 String.valueOf 로 변환.
     */
    public static MdcCloseable scope(Map<String, ?> values) {
        Map<String, String> previous = new LinkedHashMap<>(values.size());
        for (var entry : values.entrySet()) {
            Object raw = entry.getValue();
            if (raw == null) {
                continue;
            }
            String value = String.valueOf(raw);
            if (value.isBlank()) {
                continue;
            }
            previous.put(entry.getKey(), MDC.get(entry.getKey()));
            MDC.put(entry.getKey(), value);
        }
        return new MdcCloseable(previous);
    }

    /** 단일 키-값 편의. */
    public static MdcCloseable scope(String key, Object value) {
        return scope(Map.of(key, value));
    }

    /**
     * 현재 MDC 의 컨텍스트 맵을 캡처. {@link org.slf4j.MDC#getCopyOfContextMap()} 의 null
     * 안전 wrapper.
     */
    public static Map<String, String> snapshot() {
        Map<String, String> copy = MDC.getCopyOfContextMap();
        return copy == null ? Map.of() : copy;
    }

    /** {@link MDC#setContextMap(Map)} 의 null 안전 wrapper. */
    public static void restore(Map<String, String> snapshot) {
        if (snapshot == null || snapshot.isEmpty()) {
            MDC.clear();
        } else {
            MDC.setContextMap(snapshot);
        }
    }

    /** AutoCloseable 로 try-with-resources 에서 자동 복구. */
    public static final class MdcCloseable implements AutoCloseable {
        private final Map<String, String> previous;

        MdcCloseable(Map<String, String> previous) {
            this.previous = previous;
        }

        @Override
        public void close() {
            for (var entry : previous.entrySet()) {
                if (entry.getValue() == null) {
                    MDC.remove(entry.getKey());
                } else {
                    MDC.put(entry.getKey(), entry.getValue());
                }
            }
        }
    }
}
