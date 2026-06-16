package com.aipaas.anycloud.common.logging;

import ch.qos.logback.classic.pattern.MessageConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

/**
 * MessageConverter 의 message 출력에 {@link SensitiveDataRedactor} 를 적용. logback-spring.xml 의
 * conversionRule {@code redactedMsg} → 본 클래스가 매핑.
 *
 * <p>String pattern (non-JSON) profile 의 ANYCLOUD_LOG_PATTERN 에서 {@code %m} 대신
 * {@code %redactedMsg} 사용 시 활성. JSON profile 의 logstash encoder 는 별도 — message 필드에
 * redactor 적용이 어려워 본 PR 범위 밖.
 */
public class RedactingMessageConverter extends MessageConverter {

    @Override
    public String convert(ILoggingEvent event) {
        return SensitiveDataRedactor.redact(super.convert(event));
    }
}
