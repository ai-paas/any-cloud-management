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
@Schema(description = "VM 클러스터 노드 응답 DTO")
public class VmClusterNodeResponse {

    @Schema(description = "노드 역할")
    private String role;

    @Schema(description = "인스턴스 ID")
    private String instanceId;

    @Schema(description = "Public IP")
    private String publicIp;

    @Schema(description = "Private IP")
    private String privateIp;

    @Schema(description = "Public DNS")
    private String publicDns;

    @Schema(description = "SSH 명령")
    private String ssh;
}
