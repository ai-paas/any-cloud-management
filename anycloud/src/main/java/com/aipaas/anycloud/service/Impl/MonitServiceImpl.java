package com.aipaas.anycloud.service.Impl;

import com.aipaas.anycloud.error.exception.EntityNotFoundException;
import com.aipaas.anycloud.model.entity.ClusterEntity;
import com.aipaas.anycloud.repository.ClusterRepository;
import com.aipaas.anycloud.service.MonitService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

@Service("MonitServiceImpl")
@Slf4j
@RequiredArgsConstructor
public class MonitServiceImpl implements MonitService {

	private final ObjectMapper objectMapper;
	private final ClusterRepository clusterRepository;
	private final WebClient webClient;

	private String getMonitUrl(String clusterName) {
		ClusterEntity cluster = clusterRepository.findById(clusterName).orElseThrow(
				() -> new EntityNotFoundException("Cluster with Name " + clusterName + " Not Found."));
		String monitUrl = cluster.getMonitServerUrl();
		if (monitUrl == null || monitUrl.isEmpty()) {
			throw new EntityNotFoundException("Monitoring Url Not Found for cluster: " + clusterName);
		}
		log.info("Using monitUrl: {} for cluster: {}", monitUrl, clusterName);
		return monitUrl;
	}

	@Override
	public JsonNode query(
		String clusterName,
		String query,
		String time,
		String timeout,
		Integer limit,
		String lookbackDelta,
		String stats
	) {
		String monitUrl = getMonitUrl(clusterName);
		Map<String, String> queryParams1 = new LinkedHashMap<>();
		queryParams1.put("query", query);
		queryParams1.put("time", time);
		queryParams1.put("timeout", timeout);
		queryParams1.put("limit", limit != null ? String.valueOf(limit) : null);
		queryParams1.put("lookback_delta", lookbackDelta);
		queryParams1.put("stats", stats);
		Map<String, String> queryParams = queryParams1;
		try {
			String normalizedMonitUrl = monitUrl.endsWith("/") ? monitUrl.substring(0, monitUrl.length() - 1) : monitUrl;
			String queryString = queryParams.entrySet()
				.stream()
				.filter(entry1 -> entry1.getValue() != null && !entry1.getValue().isBlank())
				.map(entry -> entry.getKey() + "=" + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8).replace("+", "%20"))
				.collect(Collectors.joining("&"));
			URI uri = URI.create(
				normalizedMonitUrl + "/api/v1/query" + (queryString.isBlank() ? "" : "?" + queryString)
			);
			String responseBody = webClient.get()
				.uri(uri)
				.retrieve()
				.bodyToMono(String.class)
				.block();

			log.info("Prometheus instant query response received. uri={}", uri);
			return objectMapper.readTree(responseBody);
		} catch (Exception e) {
			log.error("Failed to execute Prometheus instant query: {}", e.getMessage(), e);
			throw new RuntimeException("Prometheus instant query failed", e);
		}
	}

	@Override
	public JsonNode queryRange(
		String clusterName,
		String query,
		String start,
		String end,
		String step,
		String timeout,
		Integer limit,
		String lookbackDelta,
		String stats
	) {
		String monitUrl = getMonitUrl(clusterName);
		Map<String, String> queryParams1 = new LinkedHashMap<>();
		queryParams1.put("query", query);
		queryParams1.put("start", start);
		queryParams1.put("end", end);
		queryParams1.put("step", step);
		queryParams1.put("timeout", timeout);
		queryParams1.put("limit", limit != null ? String.valueOf(limit) : null);
		queryParams1.put("lookback_delta", lookbackDelta);
		queryParams1.put("stats", stats);
		Map<String, String> queryParams = queryParams1;
		try {
			String normalizedMonitUrl = monitUrl.endsWith("/") ? monitUrl.substring(0, monitUrl.length() - 1) : monitUrl;
			String queryString = queryParams.entrySet()
				.stream()
				.filter(entry1 -> entry1.getValue() != null && !entry1.getValue().isBlank())
				.map(entry -> entry.getKey() + "=" + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8).replace("+", "%20"))
				.collect(Collectors.joining("&"));
			URI uri = URI.create(
				normalizedMonitUrl + "/api/v1/query_range" + (queryString.isBlank() ? "" : "?" + queryString)
			);
			String responseBody = webClient.get()
				.uri(uri)
				.retrieve()
				.bodyToMono(String.class)
				.block();

			log.info("Prometheus range query response received. uri={}", uri);
			return objectMapper.readTree(responseBody);
		} catch (Exception e) {
			log.error("Failed to execute Prometheus range query: {}", e.getMessage(), e);
			throw new RuntimeException("Prometheus range query failed", e);
		}
	}
}
