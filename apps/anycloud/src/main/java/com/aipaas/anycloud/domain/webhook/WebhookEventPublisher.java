package com.aipaas.anycloud.domain.webhook;

/**
 * 외부 webhook 으로 이벤트를 비동기 전송. 실패는 best-effort (재시도 후 메트릭만 남김).
 */
public interface WebhookEventPublisher {

    /**
     * 이벤트 전송 요청. webhook 비활성 또는 type filter 미일치 시 no-op.
     */
    void publish(WebhookEvent event);
}
