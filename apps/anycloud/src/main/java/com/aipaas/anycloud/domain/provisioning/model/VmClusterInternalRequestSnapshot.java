package com.aipaas.anycloud.domain.provisioning.model;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;
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
@Schema(description = "VM 클러스터 생성 요청 스냅샷")
public class VmClusterInternalRequestSnapshot {

    private String clusterProvider;
    private String clusterName;
    private String description;
    private String environment;
    private String region;
    private String credentialId;
    private String credentialName;
    private String masterVmSpec;
    private String workerVmSpec;
    private Integer workerCount;
    private String kubernetesVersion;
    private String podCidr;
    private String serviceCidr;
    private String osImage;
    private Boolean enableIngress;
    private Boolean enableGpuOperator;
    private Boolean dbEnabled;
    private Map<String, String> providerConfig;
}
