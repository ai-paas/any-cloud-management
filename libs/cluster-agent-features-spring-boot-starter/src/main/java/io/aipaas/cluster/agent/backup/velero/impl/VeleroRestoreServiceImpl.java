package io.aipaas.cluster.agent.backup.velero.impl;

import io.aipaas.cluster.agent.runtime.AgentSessionRegistry;
import io.aipaas.cluster.agent.backup.core.BackupException;
import io.aipaas.cluster.agent.backup.velero.VeleroCrResult;
import io.aipaas.cluster.agent.backup.velero.VeleroRestoreRequest;
import io.aipaas.cluster.agent.backup.velero.VeleroRestoreService;
import java.util.LinkedHashMap;
import java.util.Map;

public class VeleroRestoreServiceImpl implements VeleroRestoreService {

	private final VeleroCrApplier applier;

	public VeleroRestoreServiceImpl(AgentSessionRegistry sessionRegistry) {
		this.applier = new VeleroCrApplier(sessionRegistry);
	}

	@Override
	public VeleroCrResult create(String clusterName, VeleroRestoreRequest req) {
		if (req == null || req.name() == null || req.name().isBlank()) {
			throw new BackupException("INVALID_PARAMS", "restore name required");
		}
		if (req.backupName() == null || req.backupName().isBlank()) {
			throw new BackupException("INVALID_PARAMS", "backupName required");
		}
		String ns = req.namespace() == null || req.namespace().isBlank() ? "velero" : req.namespace();
		Map<String, Object> cr = buildRestoreCr(req, ns);
		applier.applyCr(clusterName, ns, cr);
		return new VeleroCrResult(clusterName, "Restore", req.name(), ns, "Submitted");
	}

	static Map<String, Object> buildRestoreCr(VeleroRestoreRequest req, String namespace) {
		Map<String, Object> cr = new LinkedHashMap<>();
		cr.put("apiVersion", "velero.io/v1");
		cr.put("kind", "Restore");

		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("name", req.name());
		metadata.put("namespace", namespace);
		cr.put("metadata", metadata);

		Map<String, Object> spec = new LinkedHashMap<>();
		spec.put("backupName", req.backupName());
		if (req.includedNamespaces() != null && !req.includedNamespaces().isEmpty()) {
			spec.put("includedNamespaces", req.includedNamespaces());
		}
		if (req.excludedNamespaces() != null && !req.excludedNamespaces().isEmpty()) {
			spec.put("excludedNamespaces", req.excludedNamespaces());
		}
		if (req.includedResources() != null && !req.includedResources().isEmpty()) {
			spec.put("includedResources", req.includedResources());
		}
		if (req.namespaceMapping() != null && !req.namespaceMapping().isEmpty()) {
			spec.put("namespaceMapping", req.namespaceMapping());
		}
		spec.put("restorePVs", req.restorePVs());
		cr.put("spec", spec);
		return cr;
	}
}
