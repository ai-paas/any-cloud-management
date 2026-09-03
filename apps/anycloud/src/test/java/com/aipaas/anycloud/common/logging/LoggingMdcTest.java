package com.aipaas.anycloud.common.logging;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class LoggingMdcTest extends AbstractUnitTest {

    @AfterEach
    void cleanMdc() {
        MDC.clear();
    }

    @Test
    void scope_setsAndRestoresMdcEntries() {
        MDC.put(LoggingMdc.CLUSTER_NAME, "preexisting");
        try (var ignored = LoggingMdc.scope(Map.of(
                LoggingMdc.CLUSTER_NAME, "demo-aws-01",
                LoggingMdc.MESSAGE_ID, "abcd1234"))) {
            assertThat(MDC.get(LoggingMdc.CLUSTER_NAME)).isEqualTo("demo-aws-01");
            assertThat(MDC.get(LoggingMdc.MESSAGE_ID)).isEqualTo("abcd1234");
        }
        // 스코프를 빠져나오면 원래 값으로 복원, 없던 키는 제거.
        assertThat(MDC.get(LoggingMdc.CLUSTER_NAME)).isEqualTo("preexisting");
        assertThat(MDC.get(LoggingMdc.MESSAGE_ID)).isNull();
    }

    @Test
    void scope_skipsNullAndBlankValues() {
        Map<String, Object> mixed = new HashMap<>();
        mixed.put(LoggingMdc.CLUSTER_NAME, "x");
        mixed.put(LoggingMdc.STEP, null);
        mixed.put(LoggingMdc.RETRY_COUNT, "  ");
        try (var ignored = LoggingMdc.scope(mixed)) {
            assertThat(MDC.get(LoggingMdc.CLUSTER_NAME)).isEqualTo("x");
            assertThat(MDC.get(LoggingMdc.STEP)).isNull();
            assertThat(MDC.get(LoggingMdc.RETRY_COUNT)).isNull();
        }
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    @Test
    void snapshotAndRestore_roundtrip() {
        MDC.put(LoggingMdc.CLUSTER_NAME, "a");
        MDC.put(LoggingMdc.STEP, "PROVISION");
        Map<String, String> snap = LoggingMdc.snapshot();
        MDC.clear();
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
        LoggingMdc.restore(snap);
        assertThat(MDC.get(LoggingMdc.CLUSTER_NAME)).isEqualTo("a");
        assertThat(MDC.get(LoggingMdc.STEP)).isEqualTo("PROVISION");
    }

    @Test
    void restore_nullSnapshotClearsMdc() {
        MDC.put(LoggingMdc.CLUSTER_NAME, "a");
        LoggingMdc.restore(null);
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }
}
