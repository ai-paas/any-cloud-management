package com.aipaas.anycloud.service.Impl;


import com.aipaas.anycloud.error.exception.EntityNotFoundException;
import com.aipaas.anycloud.model.entity.ClusterEntity;
import com.aipaas.anycloud.model.entity.MonitEntity;
import com.aipaas.anycloud.repository.ClusterRepository;
import com.aipaas.anycloud.service.MonitService;
import com.aipaas.anycloud.util.PrometheusMetricProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;

import java.net.URI;
import java.nio.charset.StandardCharsets;
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
	public MonitEntity realTimeMonit(String clusterName, Map<String, String> filter) {
		String monitUrl = getMonitUrl(clusterName);
		String filtering = mapToQueryString(filter);
		log.info("Filtering: {}", filtering);
		String cpuUsedQuery = metricProperties.resolve("cpu", "usage", filtering);
		log.info("cpuUsedQuery: {}", cpuUsedQuery);
		// TODO: 실제 필요한 쿼리 키로 변경 필요

		Double cpuUsage = executeQuery(monitUrl, cpuUsedQuery);

		log.info("cpuUsage: {}", cpuUsage);
		// 메모리, 디스크 등도 동일 방식으로 실행 후 set

		return MonitEntity.builder()
			.cpu(MonitEntity.RealTimeMonit.builder()
				.used(cpuUsage)
				.build())
			.build();
	}

	/**
	 * 단일 쿼리 결과에서 value 필드(두 번째 값) 반환
	 */
	public Double executeQuery(String monitUrl, String query) {
		try {

			String encodedQuery = UriUtils.encodeQueryParam(query, StandardCharsets.UTF_8);
			String url = monitUrl + "/api/v1/query?query=" + encodedQuery;

			log.info("Executing Query: {}", url);

			URI uri = UriComponentsBuilder.fromHttpUrl(monitUrl)
				.path("/api/v1/query")
				.queryParam("query", query) // 인코딩 안 된 원본 쿼리
				.build()
				.encode()
				.toUri();

			String responseBody = webClient.get()
				.uri(uri)  // URI 객체 넘김
				.retrieve()
				.bodyToMono(String.class)
				.block();

			JsonNode rootNode = objectMapper.readTree(responseBody);
			JsonNode resultArray = rootNode.path("data").path("result");
			if (resultArray.isArray() && resultArray.size() > 0) {
				JsonNode valueNode = resultArray.get(0).path("value");
				if (valueNode.isArray() && valueNode.size() == 2) {
					return valueNode.get(1).asDouble();
				}
			}
			throw new IllegalStateException("No valid data in Prometheus response");

		} catch (Exception e) {
			log.error("Failed to execute Prometheus query: {}", e.getMessage(), e);
			throw new RuntimeException("Prometheus query failed", e);
		}
	}

	private String mapToQueryString(Map<String, String> filter) {
		return filter.entrySet().stream()
			.sorted(Map.Entry.comparingByKey()) // 키 순 정렬 (원하지 않으면 생략 가능)
			.map(entry -> entry.getKey() + "=\"" + entry.getValue() + "\"")
			.collect(Collectors.joining(", "));
	}
}
