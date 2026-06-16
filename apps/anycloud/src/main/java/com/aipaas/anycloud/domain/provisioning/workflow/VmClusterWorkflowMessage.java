package com.aipaas.anycloud.domain.provisioning.workflow;

import io.aipaas.cluster.provisioning.core.ProvisioningRequest;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VmClusterWorkflowMessage {

    /**
     * 메시지의 전역 고유 ID. Publisher 가 발행 직전 비어 있으면 UUID 를 자동 할당한다.
     * Orchestrator 가 멱등성 가드에 사용하며 처리 완료 후
     * vm_cluster.last_processed_workflow_message_id 컬럼에 기록되어
     * RabbitMQ 의 at-least-once 재전달 시 중복 실행을 차단한다.
     */
    private String messageId;

    private String vmClusterId;
    private String clusterName;
    private String stackName;
    private VmClusterWorkflowStep step;
    private ProvisioningRequest provisioningRequest;
}
