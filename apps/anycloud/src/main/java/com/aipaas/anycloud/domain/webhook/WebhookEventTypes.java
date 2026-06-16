package com.aipaas.anycloud.domain.webhook;

/**
 * Webhook event type 상수. 외부 계약이므로 변경/제거 시 consumer (포털) 와 협의 필요.
 */
public final class WebhookEventTypes {

    public static final String VM_CLUSTER_READY = "vm-cluster.ready";
    public static final String VM_CLUSTER_FAILED = "vm-cluster.failed";
    public static final String VM_CLUSTER_BLOCKED = "vm-cluster.blocked";
    public static final String VM_CLUSTER_DELETED = "vm-cluster.deleted";

    private WebhookEventTypes() {}
}
