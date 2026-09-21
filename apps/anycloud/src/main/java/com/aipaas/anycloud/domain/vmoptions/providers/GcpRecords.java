package com.aipaas.anycloud.domain.vmoptions.providers;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/**
 * GCP Compute Engine REST API 응답의 typed projection.
 */
final class GcpRecords {

    private GcpRecords() {}

    /** {@code GET /regions} 또는 {@code /zones} 의 item. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record RegionOrZone(String name, String status) {}

    /** {@code GET /regions/{region}} 의 quotas 항목. limit 이 0 이면 그 자원을 아예 못 만든다. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record RegionDetail(String name, List<Quota> quotas) {

        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Quota(String metric, Double limit, Double usage) {}
    }

    /** {@code GET /machineTypes} 의 item. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record MachineType(String name, String description, Integer guestCpus, Integer memoryMb) {}

    /**
     * {@code GET /global/images} 의 item. {@code deprecated} 는 존재 여부만 사용 (JsonNode 유지).
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Image(
            String name,
            String id,
            String selfLink,
            String architecture,
            String creationTimestamp,
            JsonNode deprecated) {}

    /** Service account JSON 의 project_id 필드만 추출용. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record ServiceAccountJson(@JsonProperty("project_id") String projectId) {}
}
