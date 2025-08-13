package com.aipaas.anycloud.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Component
@ConfigurationProperties(prefix = "prometheus")
public class PrometheusMetricProperties {

	private Map<String, Map<String, String>> metrics;

	public Map<String, Map<String, String>> getMetrics() {
		return metrics;
	}

	public void setMetrics(Map<String, Map<String, String>> metrics) {
		this.metrics = metrics;
	}

//	public String resolve(String group, String key, Map<String, String> variables) {
//		log.info("resolve -> {}:{}:{}", group, key, variables);
//
//		Map<String, String> groupMap = metrics.get(group);
//		if (groupMap == null) {
//			throw new IllegalArgumentException("Invalid metric group: " + group);
//		}
//
//		String queryTemplate = groupMap.get(key);
//		if (queryTemplate == null) {
//			throw new IllegalArgumentException("Invalid metric key: " + key + " for group: " + group);
//		}
//
//		String query = queryTemplate;
//
//		// 변수가 있으면 치환 수행
//		if (variables != null) {
//			for (Map.Entry<String, String> entry : variables.entrySet()) {
//				String placeholder = "${" + entry.getKey() + "}";
//				String replacement = (entry.getValue() != null) ? entry.getValue() : "";
//				query = query.replace(placeholder, replacement);
//			}
//
//			// 빈 값 치환으로 인한 불필요한 콤마 정리
//			query = cleanupCommas(query);
//		}
//
//		log.info("query: {}", query);
//		return query;
//	}

}
