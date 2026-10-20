package com.aipaas.anycloud.domain.cluster;

import java.util.List;
import java.util.Set;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * cluster 상태 변화를 외부 포털로 알리는 webhook 설정입니다. 포털이 주기 조회 대신 이벤트로
 * 받게 하려는 것입니다.
 *
 * <p>{@code events} 가 비어 있으면 전부 보냅니다. 값은 {@code vm-cluster.ready},
 * {@code vm-cluster.failed}, {@code vm-cluster.blocked}, {@code vm-cluster.deleted} 입니다.
 *
 * <p>{@code signing-secret} 을 주면 HMAC-SHA256 서명을 붙입니다. 비워 두면 수신 측이 발신자를
 * 확인할 수단이 없습니다.
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
