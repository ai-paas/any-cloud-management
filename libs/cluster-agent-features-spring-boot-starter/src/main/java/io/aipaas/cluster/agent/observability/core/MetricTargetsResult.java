package io.aipaas.cluster.agent.observability.core;

/** Prometheus /api/v1/targets 응답 — JSON 그대로. */
public record MetricTargetsResult(
		String clusterName,
		String prometheusUrl,
		String raw) {}
