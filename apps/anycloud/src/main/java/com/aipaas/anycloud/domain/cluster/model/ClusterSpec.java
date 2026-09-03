package com.aipaas.anycloud.domain.cluster.model;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Source 별 typed cluster spec — Jackson discriminator 로 deserialize.
 *
 * <pre>
 * sealed interface ClusterSpec permits VmClusterSpec, RegisteredClusterSpec { }
 * </pre>
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.DEDUCTION)
@JsonSubTypes({@JsonSubTypes.Type(value = VmClusterSpec.class), @JsonSubTypes.Type(value = RegisteredClusterSpec.class)
})
@Schema(
        oneOf = {VmClusterSpec.class, RegisteredClusterSpec.class},
        description = "source 별 spec — vm 이면 Pulumi provisioning 인자, " + "registered 면 외부 K8s cluster 인증 정보")
public sealed interface ClusterSpec permits VmClusterSpec, RegisteredClusterSpec {}
