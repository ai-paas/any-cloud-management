package io.aipaas.cluster.agent.backup.velero.impl;

import io.aipaas.cluster.agent.runtime.AgentSessionRegistry;
import io.aipaas.cluster.agent.backup.core.BackupException;
import io.aipaas.cluster.agent.backup.velero.VeleroBackupRequest;
import io.aipaas.cluster.agent.backup.velero.VeleroBackupService;
import io.aipaas.cluster.agent.backup.velero.VeleroCrResult;
import java.util.LinkedHashMap;
import java.util.Map;

public class VeleroBackupServiceImpl implements VeleroBackupService {

	private final VeleroCrApplier applier;

	public VeleroBackupServiceImpl(AgentSessionRegistry sessionRegistry) {
		this.applier = new VeleroCrApplier(sessionRegistry);
	}

	@Override
	public VeleroCrResult create(String clusterName, VeleroBackupRequest request) {
		if (request == null || request.name() == null || request.name().isBlank()) {
			throw new BackupException("INVALID_PARAMS", "backup name required");
		}
		String ns = request.namespace() == null || request.namespace().isBlank() ? "velero" : request.namespace();
		Map<String, Object> cr = buildBackupCr(request, ns);
		applier.applyCr(clusterName, ns, cr);
		return new VeleroCrResult(clusterName, "Backup", request.name(), ns, "Submitted");
	}

	/** Velero Backup CR (velero.io/v1) 구성. 빈 필드는 omit — Velero default 적용. */
	static Map<String, Object> buildBackupCr(VeleroBackupRequest req, String namespace) {
		Map<String, Object> cr = new LinkedHashMap<>();
		cr.put("apiVersion", "velero.io/v1");
		cr.put("kind", "Backup");

		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("name", req.name());
		metadata.put("namespace", namespace);
		cr.put("metadata", metadata);

		Map<String, Object> spec = new LinkedHashMap<>();
		if (req.includedNamespaces() != null && !req.includedNamespaces().isEmpty()) {
			spec.put("includedNamespaces", req.includedNamespaces());
		}
		if (req.excludedNamespaces() != null && !req.excludedNamespaces().isEmpty()) {
			spec.put("excludedNamespaces", req.excludedNamespaces());
		}
		if (req.includedResources() != null && !req.includedResources().isEmpty()) {
			spec.put("includedResources", req.includedResources());
		}
		if (req.ttl() != null) {
			spec.put("ttl", req.ttl().toHours() + "h0m0s");
		}
		spec.put("snapshotVolumes", req.snapshotVolumes());
		if (req.storageLocation() != null && !req.storageLocation().isBlank()) {
			spec.put("storageLocation", req.storageLocation());
		}
		if (req.labelSelector() != null && !req.labelSelector().isBlank()) {
			Map<String, Object> selector = new LinkedHashMap<>();
			Map<String, String> matchLabels = parseLabelSelector(req.labelSelector());
			if (!matchLabels.isEmpty()) {
				selector.put("matchLabels", matchLabels);
				spec.put("labelSelector", selector);
			}
		}
		cr.put("spec", spec);
		return cr;
	}

	/** "key1=value1,key2=value2" → matchLabels map. 형식 깨지면 빈 map (caller 가 무시). */
	static Map<String, String> parseLabelSelector(String s) {
		Map<String, String> out = new LinkedHashMap<>();
		for (String pair : s.split(",")) {
			int eq = pair.indexOf('=');
			if (eq <= 0) continue;
			String k = pair.substring(0, eq).trim();
			String v = pair.substring(eq + 1).trim();
			if (!k.isEmpty()) out.put(k, v);
		}
		return out;
	}
}
