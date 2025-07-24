package com.aipaas.anycloud.util;

import java.util.Map;

public class Monitoring {

	private static final Map<String, String> clusterMetric = Map.of(
		"cpu_used", "sum(rate(node_cpu_seconds_total{mode!='idle',cluster='$1'}[5m]))by(cluster)",
		"cpu_total", "count(node_cpu_seconds_total{mode='idle',cluster='$1'})by(cluster)",
		"cpu_util",
		"round(sum(rate(node_cpu_seconds_total{mode!='idle',cluster='$1'}[5m]))by(cluster)/count(node_cpu_seconds_total{mode='idle',cluster='$1'})by(cluster)*100,0.01)",

		"memory_used",
		"sum(node_memory_MemTotal_bytes{cluster='$1'} - (node_memory_MemFree_bytes{cluster='$1'} + node_memory_Buffers_bytes{cluster='$1'} + node_memory_Cached_bytes{cluster='$1'}))by(cluster)/1024/1024/1024 ",
		"memory_total", "node_memory_MemTotal_bytes{cluster='$1'}/1024/1024/1024",
		"memory_util",
		"(sum(node_memory_MemTotal_bytes{cluster='$1'})by(cluster)-sum(node_memory_MemAvailable_bytes{cluster='$1'})by(cluster))/sum(node_memory_MemTotal_bytes{cluster='$1'})by(cluster)*100",

		"disk_used",
		"sum(node_filesystem_size_bytes{cluster='$1'} - node_filesystem_avail_bytes{cluster='$1'})by(cluster)/1000/1000/1000",
		"disk_total", "sum(node_filesystem_size_bytes{cluster='$1'})by(cluster)/1000/1000/1000",
		"disk_util",
		"round(100 - (sum(node_filesystem_avail_bytes{cluster='$1'})by(cluster)/sum(node_filesystem_size_bytes{cluster='$1'})by(cluster) * 100), 0.01)");

	public static String executeClusterMetricQuery(String clusterName, String metricKey) {
		String query = clusterMetric.get(metricKey);
		if (query != null) {
			return query.replace("$1", clusterName);
		} else {
			throw new IllegalArgumentException("Invalid metric key: " + metricKey);
		}
	}
}
