package io.aipaas.cluster.agent.observability.core;

/**
 * Cluster 내 Grafana 의 외부 접근 정보.
 *
 * @param clusterName 대상 cluster.
 * @param url         완전한 접근 URL (http(s)://host[:port]).
 * @param host        도메인 또는 IP.
 * @param port        포트 (Ingress 면 80, LB 면 service port).
 * @param exposure    "Ingress" | "LoadBalancer" — 추후 인증 게이트웨이 적용 기준.
 */
public record DashboardLocation(
		String clusterName,
		String url,
		String host,
		int port,
		String exposure) {}
