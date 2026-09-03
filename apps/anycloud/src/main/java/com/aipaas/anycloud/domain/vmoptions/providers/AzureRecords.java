package com.aipaas.anycloud.domain.vmoptions.providers;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Azure ARM REST API 응답의 typed projection. {@link JsonIgnoreProperties} 로 schema 확장 안전.
 */
final class AzureRecords {

    private AzureRecords() {}

    /** Resource SKU 단일 entry (Microsoft.Compute/skus). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record ResourceSku(
            String name,
            String resourceType,
            String tier,
            String family,
            List<String> locations,
            List<Capability> capabilities) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Capability(String name, String value) {}

    /** {@code GET /skus} 응답의 paginated wrapper. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record SkuListResponse(List<ResourceSku> value, String nextLink) {}

    /** VM image version entry. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record VmImageVersion(String name, String id) {}

    /** {@code GET /publishers/.../versions} 응답. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record VmImageListResponse(List<VmImageVersion> value) {}

    /** {@code POST /oauth2/v2.0/token} 응답. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record AccessTokenResponse(@JsonProperty("access_token") String accessToken) {}
}
