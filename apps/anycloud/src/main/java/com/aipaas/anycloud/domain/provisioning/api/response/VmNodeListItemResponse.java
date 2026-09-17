package com.aipaas.anycloud.domain.provisioning.api.response;

import com.aipaas.anycloud.domain.provisioning.query.VmClusterNodeRows;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * VM 목록의 한 행 — 클러스터가 아니라 노드 하나.
 *
 * @param infraStatus 소속 클러스터의 프로비저닝 상태. 노드별 실제 인스턴스 상태가 아니다 —
 *     그건 CSP API 를 쳐야 하고, 쿠버네티스 Ready 여부는 agent 를 거친다.
 */
@Schema(description = "VM 노드 목록 항목")
public record VmNodeListItemResponse(
        @Schema(description = "표시용 노드 이름", example = "demo-master-0") String nodeName,
        @Schema(description = "역할", example = "master") String role,
        @Schema(description = "CSP 인스턴스 식별자", example = "i-0abc") String instanceId,
        @Schema(description = "사설 IP", example = "10.0.0.1") String privateIp,
        @Schema(description = "공인 IP", example = "1.2.3.4") String publicIp,
        @Schema(description = "공인 DNS", example = "1.2.3.4") String publicDns,
        @Schema(description = "소속 클러스터", example = "demo") String clusterName,
        @Schema(description = "클라우드 제공자", example = "OCI") String clusterProvider,
        @Schema(description = "리전", example = "ap-tokyo-1") String region,
        @Schema(description = "환경", example = "dev") String environment,
        @Schema(description = "소속 클러스터의 프로비저닝 상태", example = "READY") String infraStatus) {

    public static VmNodeListItemResponse from(VmClusterNodeRows.Row row) {
        return new VmNodeListItemResponse(
                row.nodeName(),
                row.role(),
                row.instanceId(),
                row.privateIp(),
                row.publicIp(),
                row.publicDns(),
                row.clusterName(),
                row.clusterProvider(),
                row.region(),
                row.environment(),
                row.infraStatus());
    }
}
