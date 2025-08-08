package com.aipaas.anycloud.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Map;

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

	/**
	 * 특정 그룹(group)과 키(key)에 해당하는 PromQL 쿼리를 불러와
	 * $1 을 contextValue로 치환 후 반환
	 */
	public String resolve(String group, String key, String contextValue) {
		log.info("resolve -> " + group + ":" + key + ":" + contextValue);
		Map<String, String> groupMap = metrics.get(group);
		if (groupMap == null) {
			throw new IllegalArgumentException("Invalid metric group: " + group);
		}
		String queryTemplate = groupMap.get(key);
		if (queryTemplate == null) {
			throw new IllegalArgumentException("Invalid metric key: " + key + " for group: " + group);
		}
		String query = queryTemplate.replace("$1", contextValue);
		log.info("query: " + query);
		return queryTemplate.replace("$1", contextValue);
	}
}

//public class PrometheusMetricQuery {
//	private static final Map<String, String> clusterMetric = Map.of(
//		"cpu_used", "sum(rate(node_cpu_seconds_total{mode!='idle',cluster='$1'}[5m]))by(cluster)",
//		"cpu_total", "count(node_cpu_seconds_total{mode='idle',node='$1'})by(node)",
//		"cpu_util",
//		"round(sum(rate(node_cpu_seconds_total{mode!='idle',cluster='$1'}[5m]))by(cluster)/count(node_cpu_seconds_total{mode='idle',cluster='$1'})by(cluster)*100,0.01)",
//
//		"memory_used",
//		"sum(node_memory_MemTotal_bytes{cluster='$1'} - (node_memory_MemFree_bytes{cluster='$1'} + node_memory_Buffers_bytes{cluster='$1'} + node_memory_Cached_bytes{cluster='$1'}))by(cluster)/1024/1024/1024 ",
//		"memory_total", "node_memory_MemTotal_bytes{cluster='$1'}/1024/1024/1024",
//		"memory_util",
//		"(sum(node_memory_MemTotal_bytes{cluster='$1'})by(cluster)-sum(node_memory_MemAvailable_bytes{cluster='$1'})by(cluster))/sum(node_memory_MemTotal_bytes{cluster='$1'})by(cluster)*100",
//
//		"disk_used",
//		"sum(node_filesystem_size_bytes{cluster='$1'} - node_filesystem_avail_bytes{cluster='$1'})by(cluster)/1000/1000/1000",
//		"disk_total", "sum(node_filesystem_size_bytes{cluster='$1'})by(cluster)/1000/1000/1000",
//		"disk_util",
//		"round(100 - (sum(node_filesystem_avail_bytes{cluster='$1'})by(cluster)/sum(node_filesystem_size_bytes{cluster='$1'})by(cluster) * 100), 0.01)");
//
//	public static String executeClusterMetricQuery(String clusterName, String metricKey) {
//		String query = clusterMetric.get(metricKey);
//		if (query != null) {
//			return query.replace("$1", clusterName);
//		} else {
//			throw new IllegalArgumentException("Invalid metric key: " + metricKey);
//		}
//	}
//}
