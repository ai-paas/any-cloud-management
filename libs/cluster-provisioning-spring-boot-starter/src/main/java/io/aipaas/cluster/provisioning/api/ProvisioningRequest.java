package io.aipaas.cluster.provisioning.api;

import java.util.LinkedHashMap;
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
public class ProvisioningRequest {

	private String provider;
	private String clusterName;
	private String environment;
	private String region;
	private String credentialId;
	private String credentialName;
	private Map<String, String> config;
	private Map<String, String> credentialEnvironment;

	public Map<String, String> configOrEmpty() {
		return config == null ? new LinkedHashMap<>() : new LinkedHashMap<>(config);
	}

	public Map<String, String> credentialEnvironmentOrEmpty() {
		return credentialEnvironment == null ? new LinkedHashMap<>() : new LinkedHashMap<>(credentialEnvironment);
	}
}
