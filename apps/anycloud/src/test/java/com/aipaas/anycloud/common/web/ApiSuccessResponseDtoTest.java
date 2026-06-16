package com.aipaas.anycloud.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ApiSuccessResponseTest extends AbstractUnitTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void of_baseFields() {
        var r = ApiSuccessResponse.of(200, "ok", java.util.List.of("a", "b"));
        assertThat(r.success()).isTrue();
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.message()).isEqualTo("ok");
        assertThat(r.data()).isEqualTo(java.util.List.of("a", "b"));
        assertThat(r.meta()).isNull();
        assertThat(r.links()).isNull();
    }

    @Test
    void withMeta_replacesMeta_immutable() {
        var original = ApiSuccessResponse.of(200, "ok", null);
        var meta = ResponseMeta.of("req-1", "2026-05-11T00:00:00Z", 12L);
        var withMeta = original.withMeta(meta);
        assertThat(withMeta.meta().requestId()).isEqualTo("req-1");
        assertThat(original.meta()).as("immutable — original unchanged").isNull();
    }

    @Test
    void withLinks_addsHATEOAS() {
        var r = ApiSuccessResponse.of(200, "ok", null).withLinks(Map.of("self", "/v1/x", "events", "/v1/x/events"));
        assertThat(r.links()).containsEntry("self", "/v1/x").containsEntry("events", "/v1/x/events");
    }

    @Test
    void withPagedMeta_setsPaginationOnEmptyMeta() {
        var r = ApiSuccessResponse.of(200, "ok", null).withPagedMeta(50, "tok-next", null);
        assertThat(r.meta().pagination().pageSize()).isEqualTo(50);
        assertThat(r.meta().pagination().nextPageToken()).isEqualTo("tok-next");
    }

    @Test
    void jsonOmitsNullMetaAndLinks() throws Exception {
        var r = ApiSuccessResponse.of(200, "ok", java.util.List.of(1, 2));
        String s = json.writeValueAsString(r);
        assertThat(s).doesNotContain("\"meta\"").doesNotContain("\"links\"");
    }
}
