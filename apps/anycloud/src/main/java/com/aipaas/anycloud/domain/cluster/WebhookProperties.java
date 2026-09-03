package com.aipaas.anycloud.domain.cluster;

import java.util.List;
import java.util.Set;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 외부 포털/시스템에 클러스터 상태 변화를 알리기 위한 webhook 설정.
 * <p>
 * 게이트웨이 뒤의 별도 포털이 동기 polling 대신 event-driven 으로 상태를 받기 위한 핵심.
 *
 * <pre>
 * webhook:
 *   enabled: ${WEBHOOK_ENABLED:false}
 *   urls:
 *     - "https://portal.internal/anycloud/events"
 *   events:                       # filter — 비어 있으면 모두 전송
 *     - vm-cluster.ready
 *     - vm-cluster.failed
 *     - vm-cluster.blocked
 *     - vm-cluster.deleted
 *   signing-secret: ${WEBHOOK_SECRET:}    # HMAC-SHA256 서명 (선택)
 *   timeout-ms: 5000
 *   max-attempts: 3
 *   initial-interval-ms: 500
 * </pre>
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "webhook")
public class WebhookProperties {

    /** 활성 여부. 기본 false. */
    private boolean enabled = false;

    /** 전송 대상 URL 목록. 여러 곳에 동시 전송 가능. */
    private List<String> urls = List.of();

    /**
     * 전송할 이벤트 타입 화이트리스트. 비어 있으면 모든 이벤트 전송. 예:
     * vm-cluster.ready, vm-cluster.failed, vm-cluster.blocked, vm-cluster.deleted.
     */
    private Set<String> events = Set.of();

    /** HMAC-SHA256 서명용 secret. 비어 있으면 서명 미부착. */
    private String signingSecret = "";

    /** HTTP 호출 타임아웃 (ms). */
    private int timeoutMs = 5000;

    /** 실패 시 최대 시도 횟수(첫 시도 포함). */
    private int maxAttempts = 3;

    /** 첫 재시도까지 대기 (ms). 이후 지수 backoff. */
    private long initialIntervalMs = 500L;
}
