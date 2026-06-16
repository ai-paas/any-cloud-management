package io.aipaas.cluster.agent.backup.velero.impl;

import io.aipaas.cluster.agent.runtime.AgentSessionRegistry;
import io.aipaas.cluster.agent.backup.core.BackupException;
import io.aipaas.cluster.agent.backup.velero.VeleroBackupRequest;
import io.aipaas.cluster.agent.backup.velero.VeleroCrResult;
import io.aipaas.cluster.agent.backup.velero.VeleroScheduleRequest;
import io.aipaas.cluster.agent.backup.velero.VeleroScheduleService;
import java.util.LinkedHashMap;
import java.util.Map;

public class VeleroScheduleServiceImpl implements VeleroScheduleService {

	private final VeleroCrApplier applier;

	public VeleroScheduleServiceImpl(AgentSessionRegistry sessionRegistry) {
		this.applier = new VeleroCrApplier(sessionRegistry);
	}

	@Override
	public VeleroCrResult create(String clusterName, VeleroScheduleRequest req) {
		if (req == null || req.name() == null || req.name().isBlank()) {
			throw new BackupException("INVALID_PARAMS", "schedule name required");
		}
		if (req.cron() == null || req.cron().isBlank()) {
			throw new BackupException("INVALID_PARAMS", "cron required");
		}
		if (req.template() == null) {
			throw new BackupException("INVALID_PARAMS", "backup template required");
		}
		String ns = req.namespace() == null || req.namespace().isBlank() ? "velero" : req.namespace();
		Map<String, Object> cr = buildScheduleCr(req, ns);
		applier.applyCr(clusterName, ns, cr);
		return new VeleroCrResult(clusterName, "Schedule", req.name(), ns, "Submitted");
	}

	static Map<String, Object> buildScheduleCr(VeleroScheduleRequest req, String namespace) {
		Map<String, Object> cr = new LinkedHashMap<>();
		cr.put("apiVersion", "velero.io/v1");
		cr.put("kind", "Schedule");

		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("name", req.name());
		metadata.put("namespace", namespace);
		cr.put("metadata", metadata);

		Map<String, Object> spec = new LinkedHashMap<>();
		spec.put("schedule", req.cron());
		if (req.paused()) {
			spec.put("paused", true);
		}
		// Backup template — full Backup spec 을 그대로 임베드.
		Map<String, Object> tmpl = buildBackupTemplate(req.template());
		spec.put("template", tmpl);
		cr.put("spec", spec);
		return cr;
	}

	/** Backup CR 의 spec 부분만 추출 (Schedule.template 은 spec 형식). */
	private static Map<String, Object> buildBackupTemplate(VeleroBackupRequest req) {
		// Backup CR 전체에서 spec 만 꺼냄 — 중복 코드 회피.
		Map<String, Object> backupCr = VeleroBackupServiceImpl.buildBackupCr(req,
				req.namespace() == null ? "velero" : req.namespace());
		@SuppressWarnings("unchecked")
		Map<String, Object> spec = (Map<String, Object>) backupCr.get("spec");
		return spec == null ? Map.of() : spec;
	}
}
