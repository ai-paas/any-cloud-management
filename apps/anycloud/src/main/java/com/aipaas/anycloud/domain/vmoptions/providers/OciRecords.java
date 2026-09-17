package com.aipaas.anycloud.domain.vmoptions.providers;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

/** L1 — OCI REST API 응답의 typed projection. JsonNode 직접 접근 (path("foo").asText()) 을 record field 접근으로 교체해 컴파일 시점에 typo 차단. */
final class OciRecords {

    private OciRecords() {}

    /**
     * GET /regionSubscriptions 의 단일 item. status 가 "READY" 이어야 available.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record RegionSubscription(String regionName, String regionKey, String status) {}

    /** GET /availabilityDomains 의 단일 item. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record AvailabilityDomain(String name) {}

    /** GET /shapes 의 단일 item. {@code ocpus}/{@code memoryInGBs} 는 flex shape 면 응답에서 누락 가능 — record field 가 null 이면 처리 측에서 null 체크. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Shape(
            String shape,
            Integer ocpus,
            Double memoryInGBs,
            Integer gpus,
            String processorDescription,
            JsonNode shapeConfigOptions) {}

    /**
     * GET /images 의 단일 item. {@code lifecycleState} 가 "AVAILABLE" 이어야 노출 대상.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Image(
            String id,
            String displayName,
            String lifecycleState,
            String operatingSystem,
            String operatingSystemVersion,
            String timeCreated) {}
}
