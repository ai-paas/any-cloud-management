package com.aipaas.anycloud.domain.vmoptions.providers;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/** Proxmox VE API 응답 typed projection. */
final class ProxmoxRecords {

    private ProxmoxRecords() {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record AuthResponse(Auth data) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Auth(String ticket, @JsonProperty("CSRFPreventionToken") String csrfPreventionToken) {}
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Node(String node, String status) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record NodesResponse(List<Node> data) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record NodeStatus(JsonNode cpuinfo, JsonNode memory) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record NodeStatusResponse(NodeStatus data) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record VmTemplate(String name, String vmid, Integer template) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record VmTemplatesResponse(List<VmTemplate> data) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PciDevice(
            @JsonProperty("class") String deviceClass,
            @JsonProperty("device_name") String deviceName,
            @JsonProperty("vendor_name") String vendorName) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PciDevicesResponse(List<PciDevice> data) {}
}
