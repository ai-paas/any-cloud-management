package com.aipaas.anycloud.service.Impl;


import com.aipaas.anycloud.error.exception.EntityNotFoundException;
import com.aipaas.anycloud.model.entity.ClusterEntity;
import com.aipaas.anycloud.model.entity.MonitEntity;
import com.aipaas.anycloud.repository.ClusterRepository;
import com.aipaas.anycloud.service.MonitService;
import com.aipaas.anycloud.service.PrometheusQueryService;
import com.aipaas.anycloud.util.PrometheusMetricProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.core.util.Json;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service("MonitServiceImpl")
@Slf4j
@RequiredArgsConstructor
public class MonitServiceImpl implements MonitService {

	private final ObjectMapper objectMapper;
	private final ClusterRepository clusterRepository;
	private final PrometheusMetricProperties metricProperties;
	private final WebClient webClient;
	private final PrometheusQueryService prometheusQueryService;

	private String getMonitUrl(String clusterName) {
		ClusterEntity cluster = clusterRepository.findByName(clusterName).orElseThrow(
			() -> new EntityNotFoundException("Cluster with Name " + clusterName + " Not Found.")
		);
		String monitUrl = cluster.getMonitServerUrl();
		if (monitUrl == null || monitUrl.isEmpty()) {
			throw new EntityNotFoundException("Monitoring Url Not Found for cluster: " + clusterName);
		}
		log.info("Using monitUrl: {} for cluster: {}", monitUrl, clusterName);
		return monitUrl;
	}

	@Override
	public List<MonitEntity.NodeStatus> nodeStatus(String clusterName) {
		String monitUrl = getMonitUrl(clusterName);
		String query = prometheusQueryService.resolve("node", "status", null);

		Map<String, MonitEntity.NodeStatus.NodeStatusBuilder> builderMap = new HashMap<>();
		Map<String, MonitEntity.Condition> conditionMap = new HashMap<>();

		JsonNode result = executeQueryRaw(monitUrl, query);
		for (JsonNode node : result) {
			JsonNode metric = node.get("metric");
			JsonNode value = node.get("value").get(1);
			String nodeName = metric.get("node").asText();
			String nodeIp = metric.get("instance").asText().split(":")[0];
			String conditionStr = metric.get("condition").asText();
			String statusStr = metric.get("status").asText();

			MonitEntity.Condition cond = conditionMap.get(nodeName);
			if (cond == null) {
				cond = MonitEntity.Condition.builder().build();
				conditionMap.put(nodeName, cond);
			}

			switch (conditionStr) {
				case "Ready":
					cond.setReady(Boolean.parseBoolean(statusStr));
					break;
				case "DiskPressure":
					cond.setDiskPressure(Boolean.parseBoolean(statusStr));
					break;
				case "MemoryPressure":
					cond.setMemoryPressure(Boolean.parseBoolean(statusStr));
					break;
				case "PIDPressure":
					cond.setPIDPressure(Boolean.parseBoolean(statusStr));
					break;
				case "NetworkUnavailable":
					cond.setNetworkPressure(Boolean.parseBoolean(statusStr));
					break;
			}

			MonitEntity.NodeStatus.NodeStatusBuilder builder = builderMap.get(nodeName);
			if (builder == null) {
				builder = MonitEntity.NodeStatus.builder()
					.nodeName(nodeName)
					.nodeIp(nodeIp)
					.condition(cond);
				builderMap.put(nodeName, builder);
			}
		}

		return builderMap.values().stream()
			.map(MonitEntity.NodeStatus.NodeStatusBuilder::build)
			.collect(Collectors.toList());
	}

	@Override
	public Object resourceMonit(String clusterName, String Type, String key, Map<String, String> QueryFilter) {

		String monitUrl = getMonitUrl(clusterName);

		String resolve_query = prometheusQueryService.resolveFromParams(Type, key, QueryFilter);

		String usage_query = prometheusQueryService.resolveFromParams(Type, "usage_namespace", QueryFilter);
		JsonNode result= executeQueryRaw(monitUrl, resolve_query);

		if(result.isArray()) {
			ArrayList<MonitEntity.RealTimeMonit> monitArray = new ArrayList<>();
			for (JsonNode node : result) {
				MonitEntity.RealTimeMonit usage_val = MonitEntity.RealTimeMonit.builder()
					.info(node.get("metric"))
					.value(node.get("value").get(1).asDouble())
					.build();
				monitArray.add(usage_val);
			}
			return monitArray;
		}else{
			MonitEntity.RealTimeMonit monit = MonitEntity.RealTimeMonit.builder()
				.info(result.get(0).get("metric"))
				.value(result.get(0).get("value").get(1).asDouble())
				.build();

			return monit;
		}


//		JsonNode result_usage = executeQueryRaw(monitUrl, usage_query);
//
//
//
//		MonitEntity.RealTimeMonit total = MonitEntity.RealTimeMonit.builder()
//			.info(result_total.get(0).get("metric"))
//			.value(result_total.get(0).get("value").get(1).asDouble())
//			.build();

//		for (JsonNode node : result_total) {
//			MonitEntity.ResourceStatus.ResourceStatusBuilder total_val = MonitEntity.ResourceStatus.builder()
//				.total(MonitEntity.RealTimeMonit.builder()
//					.info(node.get("metric"))
//					.value(node.get("value").get(1).asDouble())
//					.build());
//			total.add(total_val.build());
//		}

//		for (JsonNode node : result_usage) {
//			MonitEntity.RealTimeMonit usage_val = MonitEntity.RealTimeMonit.builder()
//				.info(node.get("metric"))
//				.value(node.get("value").get(1).asDouble())
//				.build();
//			usage.add(usage_val);
//		}
//		return MonitEntity.ResourceStatus.builder()
//			.total(total)
//			.usage(usage)
//			.build();
	}


//
//	@Override
//	public MonitEntity realTimeMonit(String clusterName, Map<String, String> filter) {
//		String monitUrl = getMonitUrl(clusterName);

	/// /		String filtering = mapToQueryString(filter);
	/// /		log.info("Filtering: {}", filtering);
//		String cpuUsedQuery = prometheusQueryService.resolve("node", "status", null);
//		log.info("cpuUsedQuery: {}", cpuUsedQuery);
//		// TODO: 실제 필요한 쿼리 키로 변경 필요
//
//		JsonNode result = executeQueryRaw(monitUrl, cpuUsedQuery);
//		Double cpuUsage = result.get("value").get(1).asDouble();
//
//		// 메모리, 디스크 등도 동일 방식으로 실행 후 set
//
//		return MonitEntity.builder()
//			.(MonitEntity.RealTimeMonit.builder()
//				.usage(cpuUsage)
//				.build())
//			.build();
//	}
	private JsonNode executeQueryRaw(String monitUrl, String query) {
		try {
			String encodedQuery = UriUtils.encode(query, StandardCharsets.UTF_8);

			log.error("query : {} ", query);
			log.error("encodedQuery : {} ", encodedQuery);

			URI uri = UriComponentsBuilder.fromHttpUrl(monitUrl)
				.path("/api/v1/query")
				.queryParam("query", encodedQuery) // + 기호가 미리 인코딩된 쿼리
				.build(true) // 이미 인코딩된 값이므로 추가 인코딩 안함
				.toUri();


			String responseBody = webClient.get()
				.uri(uri)
				.retrieve()
				.bodyToMono(String.class)
				.block();

			JsonNode rootNode = objectMapper.readTree(responseBody);
			JsonNode resultArray = rootNode.path("data").path("result");

			if (resultArray.isArray() && resultArray.size() > 0) {
				return resultArray;
			}
			throw new IllegalStateException("No valid data in Prometheus response");

		} catch (Exception e) {
			log.error("Failed to execute Prometheus query: {}", e.getMessage(), e);
			throw new RuntimeException("Prometheus query failed", e);
		}
	}
}
