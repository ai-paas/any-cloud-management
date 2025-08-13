package com.aipaas.anycloud.util;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

public class PrometheusFilterBuilder {

	/**
	 * Query Parameters를 Prometheus 변수 맵으로 변환
	 *
	 * @param queryParams HTTP 요청의 Query Parameters
	 * @return Prometheus 쿼리에서 사용할 변수 맵
	 */
	public Map<String, String> buildVariables(Map<String, String> queryParams) {
		Map<String, String> variables = new HashMap<>();

		// 1. NODE_FILTER 생성
		String nodeFilter = buildFilter(queryParams, "node");
		if (!nodeFilter.isEmpty()) {
			variables.put("NODE_FILTER", nodeFilter);
		}

		// 2. NAMESPACE_FILTER 생성
		String namespaceFilter = buildFilter(queryParams, "namespace");
		if (!namespaceFilter.isEmpty()) {
			variables.put("NAMESPACE_FILTER", namespaceFilter);
		}

		// 3. POD_FILTER 생성
		String podFilter = buildFilter(queryParams, "pod");
		if (!podFilter.isEmpty()) {
			variables.put("POD_FILTER", podFilter);
		}

		// 4. INSTANCE 관련 변수들
		String instance = queryParams.get("instance");
		if (instance != null && !instance.isEmpty()) {
			variables.put("INSTANCE_FILTER", "instance=\"" + instance + "\"");
			variables.put("INSTANCE_VALUE", instance);
		}

		// 5. 시간 범위
		String timeRange = queryParams.get("timeRange");
		if (timeRange != null && !timeRange.isEmpty()) {
			variables.put("TIME_RANGE", "[" + timeRange + "]");
		} else {
			variables.put("TIME_RANGE", "[5m]"); // 기본값
		}

		return variables;
	}

	/**
	 * 특정 키에 대한 Prometheus 필터 문자열 생성
	 *
	 * @param queryParams 쿼리 파라미터들
	 * @param key 필터링할 키 (node, namespace, pod 등)
	 * @return Prometheus 레이블 필터 문자열
	 */
	private String buildFilter(Map<String, String> queryParams, String key) {
		String value = queryParams.get(key);
		if (value == null || value.isEmpty()) {
			return "";
		}

		// 정확한 매칭 vs 정규식 매칭 구분
		String operator = queryParams.getOrDefault(key + "_op", "=");

		switch (operator) {
			case "=":
				return key + "=\"" + value + "\"";
			case "=~":
				return key + "=~\"" + value + "\"";
			case "!=":
				return key + "!=\"" + value + "\"";
			case "!~":
				return key + "!~\"" + value + "\"";
			default:
				return key + "=\"" + value + "\"";
		}
	}

	/**
	 * 여러 개의 레이블 필터를 합쳐서 하나의 필터 문자열로 만들기
	 *
	 * @param filters 개별 필터들
	 * @return 결합된 필터 문자열
	 */
	public String combineFilters(String... filters) {
		return Arrays.stream(filters)
			.filter(filter -> filter != null && !filter.isEmpty())
			.collect(Collectors.joining(", "));
	}


}
