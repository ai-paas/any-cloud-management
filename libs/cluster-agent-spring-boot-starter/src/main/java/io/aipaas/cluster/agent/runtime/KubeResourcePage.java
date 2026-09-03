package io.aipaas.cluster.agent.runtime;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * K8s list response 의 페이지 단위 — agent path 반환 타입.
 * <ul>
 *   <li>{@code namespace} = null 이면 cluster-scoped 리소스</li>
 *   <li>{@code continueToken} = null/blank 이면 마지막 페이지</li>
 *   <li>{@code items} = K8s list 응답의 items 배열 (JsonNode 그대로)</li>
 * </ul>
 */
public record KubeResourcePage(
		String clusterName,
		String namespace,
		String kind,
		JsonNode items,
		String continueToken,
		int returnedCount) {}
