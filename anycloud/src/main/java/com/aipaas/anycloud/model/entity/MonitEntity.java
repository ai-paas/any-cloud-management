package com.aipaas.anycloud.model.entity;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Map;

import lombok.*;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MonitEntity implements Serializable {


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
	@Setter
	@Builder
	@NoArgsConstructor
	@AllArgsConstructor
	public static class NodeStatus{
		private String nodeName;
		private String nodeIp;
		private Condition condition;
	}


	@Builder @Getter @Setter
	public static class Condition {
		private Boolean ready;
		private Boolean diskPressure;
		private Boolean MemoryPressure;
		private Boolean PIDPressure;
		private Boolean NetworkPressure;
	}

	@Getter
	@Builder
	@NoArgsConstructor
	@AllArgsConstructor
	public static class RealTimeMonit {

		@JsonProperty
		@Schema(title = "info")
		private JsonNode info;

		@NotBlank
		@JsonProperty
		@Schema(title = "value")
		private double value;

	}
	@Getter
	@Builder
	@NoArgsConstructor
	@AllArgsConstructor
	public static class ResourceStatus {
		private RealTimeMonit total;
		private ArrayList<RealTimeMonit> usage;
	}

}
