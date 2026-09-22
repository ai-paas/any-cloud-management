package com.aipaas.anycloud.domain.vmoptions.api;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * providerSpec 키가 가질 수 있는 값 하나.
 *
 * <p>식별자만 내려보내면 화면에 {@code ocid1.compartment.oc1..aaaa…} 가 그대로 뜬다. 고를 수는
 * 있어도 무엇을 고르는지는 알 수 없다.
 */
@Schema(description = "설정 키가 가질 수 있는 값")
public record ConfigOption(
        @Schema(description = "서버로 보낼 값", example = "ocid1.compartment.oc1..aaaa") String value,
        @Schema(description = "화면에 띄울 이름. 식별자가 곧 이름이면 value 와 같다", example = "prod") String label) {

    /** 식별자가 곧 사람이 읽는 이름인 경우 — IBM zone, Proxmox 노드 이름 등. */
    public static ConfigOption of(String value) {
        return new ConfigOption(value, value);
    }
}
