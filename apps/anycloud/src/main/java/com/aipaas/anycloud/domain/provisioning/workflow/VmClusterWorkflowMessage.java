package com.aipaas.anycloud.domain.provisioning.workflow;

import io.aipaas.cluster.provisioning.api.ProvisioningRequest;
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

    /** 메시지의 전역 고유 ID. Publisher 가 발행 직전 비어 있으면 UUID 를 자동 할당. */
    private String messageId;

    private String vmClusterId;
    private String clusterName;
    private String stackName;
    private VmClusterWorkflowStep step;
    private ProvisioningRequest provisioningRequest;
}
