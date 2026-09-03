package com.aipaas.anycloud.common.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.spi.ILoggingEvent;
import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LoggingMdcConverterTest extends AbstractUnitTest {

    private final LoggingMdcConverter converter = new LoggingMdcConverter();

    @Test
    void emptyMdc_returnsEmptyString() {
        ILoggingEvent event = mock(ILoggingEvent.class);
        when(event.getMDCPropertyMap()).thenReturn(Map.of());
        assertThat(converter.convert(event)).isEqualTo("");
    }

    @Test
    void nullMdc_returnsEmptyString() {
        ILoggingEvent event = mock(ILoggingEvent.class);
        when(event.getMDCPropertyMap()).thenReturn(null);
        assertThat(converter.convert(event)).isEqualTo("");
    }

    @Test
    void rendersOrderedAbbreviations() {
        Map<String, String> mdc = new LinkedHashMap<>();
        mdc.put(LoggingMdc.CLUSTER_NAME, "demo-aws-01");
        mdc.put(LoggingMdc.MESSAGE_ID, "abcdef1234567890");
        mdc.put(LoggingMdc.STEP, "BOOTSTRAP");
        mdc.put(LoggingMdc.RETRY_COUNT, "2");
        ILoggingEvent event = mock(ILoggingEvent.class);
        when(event.getMDCPropertyMap()).thenReturn(mdc);

        String out = converter.convert(event);
        // messageId 는 앞 8자만 표시.
        assertThat(out).isEqualTo("[cluster=demo-aws-01 msg=abcdef12 step=BOOTSTRAP retry=2] ");
    }

    @Test
    void requestContextRendersBeforeWorkflowContext() {
        // B6 회귀 방지 — request 단위(req/ip) 가 workflow(cluster/...) 보다 먼저 표시.
        Map<String, String> mdc = new LinkedHashMap<>();
        mdc.put(LoggingMdc.CLUSTER_NAME, "demo-aws-01");
        mdc.put(LoggingMdc.REQUEST_ID, "abc12345");
        mdc.put(LoggingMdc.CLIENT_IP, "10.0.0.1");
        ILoggingEvent event = mock(ILoggingEvent.class);
        when(event.getMDCPropertyMap()).thenReturn(mdc);

        assertThat(converter.convert(event)).isEqualTo("[req=abc12345 ip=10.0.0.1 cluster=demo-aws-01] ");
    }

    @Test
    void skipsBlankValuesButRendersOthers() {
        Map<String, String> mdc = new LinkedHashMap<>();
        mdc.put(LoggingMdc.CLUSTER_NAME, "x");
        mdc.put(LoggingMdc.STEP, "");
        ILoggingEvent event = mock(ILoggingEvent.class);
        when(event.getMDCPropertyMap()).thenReturn(mdc);

        assertThat(converter.convert(event)).isEqualTo("[cluster=x] ");
    }
}
