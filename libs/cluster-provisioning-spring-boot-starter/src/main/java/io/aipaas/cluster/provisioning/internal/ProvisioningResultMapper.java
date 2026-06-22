package io.aipaas.cluster.provisioning.internal;

import io.aipaas.cluster.provisioning.api.ProvisioningResult;
import io.aipaas.cluster.provisioning.api.exception.ProvisioningResultValidationException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;

/**
 * Pulumi raw output (Map&lt;String, Object&gt;) 을 {@link ProvisioningResult} record 로 변환.
 *
 * <p>표준 키 외의 raw 필드는 모두 {@code providerNative} Map 에 격리되어 provider 별 확장 필드를
 * 잃지 않는다. 매핑 직후 jakarta.validation 으로 schema 정합성을 검증하며, 위반 발견 시
 * {@link ProvisioningResultValidationException} 을 던진다.
 */
@Slf4j
public class ProvisioningResultMapper {

	private static final Set<String> STANDARD_KEYS = Set.of(
			"provider",
			"clusterName",
			"apiServerUrl",
			"masterPublicIp",
			"masterPrivateIp",
			"sshPrivateKeyPem",
			"kubeconfigFetchCommand",
			"nodes",
			"publicDns",
			"dbEndpoint");

	private final Validator validator;

	/**
	 * 명시 ctor. autoconfig 의 @Bean 메서드가 호출.
	 */
	public ProvisioningResultMapper(Validator validator) {
		this.validator = validator;
	}

	public ProvisioningResult map(Map<String, Object> raw) {
		if (raw == null) {
			raw = Collections.emptyMap();
		}

		ProvisioningResult output = new ProvisioningResult(
				stringValue(raw.get("provider")),
				stringValue(raw.get("clusterName")),
				stringValue(raw.get("apiServerUrl")),
				stringValue(raw.get("masterPublicIp")),
				stringValue(raw.get("masterPrivateIp")),
				stringValue(raw.get("sshPrivateKeyPem")),
				stringValue(raw.get("kubeconfigFetchCommand")),
				nodesList(raw.get("nodes")),
				stringValue(raw.get("publicDns")),
				stringValue(raw.get("dbEndpoint")),
				providerNative(raw));

		Set<ConstraintViolation<ProvisioningResult>> violations = validator.validate(output);
		if (!violations.isEmpty()) {
			log.error("ProvisioningResult validation failed for provider={}, cluster={}: {}",
					output.provider(), output.clusterName(), violations);
			throw new ProvisioningResultValidationException(violations);
		}
		return output;
	}

	private static String stringValue(Object value) {
		return value == null ? null : String.valueOf(value);
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> nodesList(Object value) {
		if (value instanceof List<?> list) {
			List<Map<String, Object>> result = new ArrayList<>(list.size());
			for (Object item : list) {
				if (item instanceof Map<?, ?> map) {
					result.add((Map<String, Object>) map);
				}
			}
			return result;
		}
		// nodes 가 JSON string 으로 export 된 경우 (Pulumi SDK 의 일부 typed deserialize path 우회).
		if (value instanceof String s && !s.isBlank()) {
			try {
				return NODES_JSON.readValue(s, NODES_TYPE);
			} catch (Exception e) {
				log.warn("Failed to parse nodes JSON string: {}", e.getMessage());
			}
		}
		return new ArrayList<>();
	}

	private static final com.fasterxml.jackson.databind.ObjectMapper NODES_JSON =
			new com.fasterxml.jackson.databind.ObjectMapper();
	private static final com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>> NODES_TYPE =
			new com.fasterxml.jackson.core.type.TypeReference<>() {};

	private static Map<String, Object> providerNative(Map<String, Object> raw) {
		Map<String, Object> nativeMap = new HashMap<>();
		Set<String> seen = new HashSet<>(STANDARD_KEYS);
		for (Map.Entry<String, Object> entry : raw.entrySet()) {
			if (!seen.contains(entry.getKey())) {
				nativeMap.put(entry.getKey(), entry.getValue());
			}
		}
		return nativeMap;
	}
}
