package io.aipaas.cluster.agent.observability.core;

/** Alertmanager /api/v2/alerts 응답 — JSON 그대로. */
public record AlertsResult(
		String clusterName,
		String alertmanagerUrl,
		String raw) {}
