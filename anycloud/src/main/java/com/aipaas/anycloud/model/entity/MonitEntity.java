package com.aipaas.anycloud.model.entity;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import java.io.Serializable;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MonitEntity implements Serializable {

	// Cluster Util
	// @Builder.Default
	// private Map<String,String> clusterMetric = Map.of(
	// "cpu_usage",
	// "sum(rate(node_cpu_seconds_total{mode!=\"idle\",$1}[5m]))by(cluster)",
	// "cpu_total", "count(node_cpu_seconds_total{mode='idle',$1}) by (cluster)",
	// "cpu_util","round(sum(rate(node_cpu_seconds_total{mode!=\"idle\",$1}[5m]))by(cluster)/
	// count(node_cpu_seconds_total{mode='idle',$1}) by (cluster) * 100, 0.01)"
	// );

	private RealTimeMonit cpu;
	private RealTimeMonit memory;
	private RealTimeMonit disk;

	//
	// public String executeClusterMetricQuery(String clusterName, String metricKey)
	// {
	// String query = clusterMetric.get(metricKey);
	// if (query != null) {
	// query.replace("$1", "cluster={clusterName}");
	// return query;
	// } else {
	// throw new IllegalArgumentException("Invalid metric key: " + metricKey);
	// }
	// }
	@Getter
	@Builder
	@NoArgsConstructor
	@AllArgsConstructor
	public static class RealTimeMonit {

		@NotBlank
		@JsonProperty
		@Schema(title = "MH name", example = "MH-system")
		private double util;

		@JsonProperty
		@Schema(title = "전체 value", example = "이동형병원-1")
		private double total;

		@NotBlank
		@JsonProperty
		@Schema(title = "사용량 value", example = "0.0.0.0")
		private double used;

	}

	@Getter
	@Builder
	@NoArgsConstructor
	@AllArgsConstructor
	public static class RealTimeMonitDto {

		@NotBlank
		@JsonProperty
		@Schema(title = "MH name", example = "MH-system")
		private double cpuUsage;

		@JsonProperty
		@Schema(title = "MH description", example = "이동형병원-1")
		private double memoryUsage;

		@NotBlank
		@JsonProperty
		@Schema(title = "MH ip Address", example = "0.0.0.0")
		private double diskUsage;

	}

}
