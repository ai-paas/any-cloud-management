package com.aipaas.anycloud.domain.vmoptions.providers;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

/** Keystone / Nova / Glance API 응답 typed projection. */
final class OpenStackRecords {

    private OpenStackRecords() {}

    /** Keystone {@code POST /auth/tokens} 응답 body. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record AuthResponse(Token token) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Token(List<Service> catalog) {}
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Service(String type, String name, List<Endpoint> endpoints) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Endpoint(String region, String url, @JsonProperty("interface") String iface) {}

    /** Nova {@code GET /flavors/detail}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Flavor(
            String id,
            String name,
            Integer vcpus,
            Integer ram,
            Integer disk,
            @JsonProperty("extra_specs") Map<String, String> extraSpecs) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record FlavorsResponse(List<Flavor> flavors) {}

    /** Glance {@code GET /v2/images}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Image(
            String id,
            String name,
            String architecture,
            String owner,
            String visibility,
            @JsonProperty("created_at") String createdAt) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ImagesResponse(List<Image> images) {}
}
