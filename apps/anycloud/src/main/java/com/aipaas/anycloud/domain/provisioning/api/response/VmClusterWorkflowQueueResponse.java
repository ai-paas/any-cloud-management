package com.aipaas.anycloud.domain.provisioning.api.response;

import io.swagger.v3.oas.annotations.media.Schema;
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
@Schema(description = "VM 클러스터 워크플로우 큐 상태 응답 DTO")
public class VmClusterWorkflowQueueResponse {

    @Schema(description = "RabbitMQ workflow 활성화 여부")
    private boolean workflowEnabled;

    @Schema(description = "큐 이름")
    private String queueName;

    @Schema(description = "큐 유형", example = "PRIMARY")
    private String queueType;

    @Schema(description = "연결된 routing key", example = "vm-cluster.provision")
    private String routingKey;

    @Schema(description = "DLQ 전달 활성화 여부", example = "true")
    private boolean deadLetterEnabled;

    @Schema(description = "큐 메시지 수")
    private Integer messageCount;

    @Schema(description = "큐 소비자 수")
    private Integer consumerCount;
}
