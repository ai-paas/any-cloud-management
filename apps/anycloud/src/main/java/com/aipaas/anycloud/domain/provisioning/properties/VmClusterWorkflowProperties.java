package com.aipaas.anycloud.domain.provisioning.properties;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "vm-cluster-workflow")
public class VmClusterWorkflowProperties {

    private boolean enabled = false;
    private boolean workerEnabled = false;
    private String exchange = "vm-cluster.workflow";
    private String deadLetterExchange = "vm-cluster.workflow.dlx";
    private String deadLetterQueue = "vm-cluster.workflow.dlq";
    private String provisionQueue = "vm-cluster.provision";
    private String bootstrapQueue = "vm-cluster.bootstrap";
    private String verifyQueue = "vm-cluster.verify";
    private String destroyQueue = "vm-cluster.destroy";
    private String provisionRoutingKey = "vm-cluster.provision";
    private String bootstrapRoutingKey = "vm-cluster.bootstrap";
    private String verifyRoutingKey = "vm-cluster.verify";
    private String destroyRoutingKey = "vm-cluster.destroy";
    private String deadLetterRoutingKey = "vm-cluster.dead-letter";
    private int maxAttempts = 3;
    private long initialIntervalMs = 1000L;
    private double multiplier = 2.0d;
    private long maxIntervalMs = 10000L;

    /**
     * PROVISION 단계에서 retry 임계 초과로 BLOCKED/FAILED 가 된 경우, 부분 생성된 클라우드
     * 리소스를 자동으로 {@code pulumi destroy} 한 뒤 entity 를 DELETED 로 정리할지 여부.
     * <p>
     * 기본 false. 자동 cleanup 은 데이터 손실 가능성이 있으므로 운영자가 명시적으로 활성화필요.
     * BOOTSTRAP/VERIFY 단계 실패는 리소스가 이미 살아있을 가능성이 높아 자동 cleanup 에서 제외.
     */
    private boolean autoCleanupOnProvisionFailure = false;
}
