package com.aipaas.anycloud.domain.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WebhookEventTest extends AbstractUnitTest {

    @Test
    void of_generatesIdAndTimestamp() {
        WebhookEvent e = WebhookEvent.of(
                WebhookEventTypes.VM_CLUSTER_READY, Map.of("clusterName", "demo-aws-01", "status", "READY"));
        assertThat(e.id()).isNotBlank();
        assertThat(e.timestamp()).matches("^\\d{4}-\\d{2}-\\d{2}T.*Z$");
        assertThat(e.type()).isEqualTo(WebhookEventTypes.VM_CLUSTER_READY);
        assertThat(e.data()).containsEntry("clusterName", "demo-aws-01");
    }

    @Test
    void of_nullData_yieldsEmptyMap() {
        WebhookEvent e = WebhookEvent.of(WebhookEventTypes.VM_CLUSTER_DELETED, null);
        assertThat(e.data()).isEmpty();
    }

    @Test
    void withLinks_setsLinks() {
        WebhookEvent e = WebhookEvent.of(WebhookEventTypes.VM_CLUSTER_READY, Map.of("clusterName", "demo-aws-01"))
                .withLinks(Map.of("resource", "/v1/clusters/demo-aws-01"));
        assertThat(e.links()).containsEntry("resource", "/v1/clusters/demo-aws-01");
    }

    @Test
    void eventTypeConstants() {
        // 외부 계약 — 오타나 변경 시 즉시 잡힘.
        assertThat(WebhookEventTypes.VM_CLUSTER_READY).isEqualTo("vm-cluster.ready");
        assertThat(WebhookEventTypes.VM_CLUSTER_FAILED).isEqualTo("vm-cluster.failed");
        assertThat(WebhookEventTypes.VM_CLUSTER_BLOCKED).isEqualTo("vm-cluster.blocked");
        assertThat(WebhookEventTypes.VM_CLUSTER_DELETED).isEqualTo("vm-cluster.deleted");
    }
}
