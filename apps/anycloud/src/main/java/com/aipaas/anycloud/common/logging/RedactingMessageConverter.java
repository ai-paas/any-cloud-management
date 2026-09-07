package com.aipaas.anycloud.common.logging;

import ch.qos.logback.classic.pattern.MessageConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

/** MessageConverter 의 message 출력에 {@link SensitiveDataRedactor} 를 적용. */
public class RedactingMessageConverter extends MessageConverter {

    @Override
    public String convert(ILoggingEvent event) {
        return SensitiveDataRedactor.redact(super.convert(event));
    }
}
