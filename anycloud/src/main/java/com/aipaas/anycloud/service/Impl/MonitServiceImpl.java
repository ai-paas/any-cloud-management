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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
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

		JsonNode result = executeQueryRaw(monitUrl,"query" ,query, null);
		for (JsonNode node : result) {
			JsonNode metric = node.get("metric");
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
		JsonNode result;

		if (QueryFilter.get("duration") != null) {
			Map<String, Long> timeQueryParams = timeRangeCreate(QueryFilter.get("duration"));
			result = executeQueryRaw(monitUrl, "query_range", resolve_query,timeQueryParams );
			ArrayList<MonitEntity.Values> valuesArrayList = new ArrayList<>();
			ArrayList<MonitEntity.MetrixMonit> metrixMonits = new ArrayList<>();
			for(JsonNode node : result) {
				for (JsonNode values : node.get("values")) {
					MonitEntity.Values value = MonitEntity.Values.builder()
						.time(UnixToDate(values.get(0).toString()))
						.value(values.get(1).asDouble())
						.build();
					valuesArrayList.add(value);
				}
				MonitEntity.MetrixMonit metrixMonit = MonitEntity.MetrixMonit.builder()
					.info(node.get("metric"))
					.values(valuesArrayList)
					.build();
				metrixMonits.add(metrixMonit);
			}

			return metrixMonits;
		} else {
			result = executeQueryRaw(monitUrl, "query", resolve_query, null);
			if (result.isArray()) {
				ArrayList<MonitEntity.VectorMonit> monitArray = new ArrayList<>();
				for (JsonNode node : result) {
					MonitEntity.VectorMonit usage_val = MonitEntity.VectorMonit.builder()
						.info(node.get("metric"))
						.value(node.get("value").get(1).asDouble())
						.build();
					monitArray.add(usage_val);
				}
				return monitArray;
			} else {
				MonitEntity.VectorMonit monit = MonitEntity.VectorMonit.builder()
					.info(result.get(0).get("metric"))
					.value(result.get(0).get("value").get(1).asDouble())
					.build();

				return monit;
			}
		}
	}
	private JsonNode executeQueryRaw(String monitUrl, String metricType, String query, Map<String, Long> timeQueryParams) {
		try {
			String encodedQuery = UriUtils.encode(query, StandardCharsets.UTF_8);

			log.error("query : {} ", query);
			log.error("encodedQuery : {} ", encodedQuery);
			UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(monitUrl)
				.path("/api/v1/" + metricType)
				.queryParam("query", encodedQuery);

			// query_range면 시간 파라미터 추가
			if ("query_range".equals(metricType) && timeQueryParams!= null) {
				builder.queryParam("start", timeQueryParams.get("start"))
					.queryParam("end",timeQueryParams.get("end"))
					.queryParam("step", timeQueryParams.get("step"));
			}
			URI uri = builder.build(true).toUri();
//			URI uri = UriComponentsBuilder.fromHttpUrl(monitUrl)
//				.path("/api/v1/"+ metricType)
//				.queryParam("query", encodedQuery) // + 기호가 미리 인코딩된 쿼리
//				.build(true) // 이미 인코딩된 값이므로 추가 인코딩 안함
//				.toUri();


			String responseBody = webClient.get()
				.uri(uri)
				.retrieve()
				.bodyToMono(String.class)
				.block();

			JsonNode rootNode = objectMapper.readTree(responseBody);
			log.error("responseBody : {} ", responseBody);
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

	private Map<String,Long> timeRangeCreate(String duration){
		Map<String,Long> timeRange = new HashMap<>();
		if (duration != null && !duration.isEmpty()) {

			// 1. duration 파싱 (숫자 검증)
			long durationInput;
			try {
				durationInput = Long.parseLong(duration);
			} catch (NumberFormatException e) {
				throw new IllegalArgumentException("duration 값이 숫자가 아닙니다: " + duration);
			}

			// 2. duration 단위 판별 (기본: 분)
			//    1시간 이상인데 10000 이상이면 초 단위로 간주
			boolean isSeconds = durationInput > 10000;
			long durationSeconds = isSeconds ? durationInput : durationInput * 60;

			// 3. 기준 시각 하나로 고정
			long end = Instant.now().getEpochSecond();
			long start = end - durationSeconds;

			// 4. step 계산 (20 포인트)
			int points = 20;
			long step = (end - start) / points;

			// 5. 사람이 읽기 쉽게 변환
			DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
				.withZone(ZoneId.of("Asia/Seoul"));
			String startHuman = formatter.format(Instant.ofEpochSecond(start));
			String endHuman = formatter.format(Instant.ofEpochSecond(end));

			// 6. 로그 출력
			log.info("[Prometheus Time Range]");
			log.info("duration (입력): " + durationInput + (isSeconds ? " sec" : " min"));
			log.info("start = " + start + " (" + startHuman + ")");
			log.info("end   = " + end + " (" + endHuman + ")");
			log.info("step  = " + step + " sec");

			// 7. 쿼리 문자열 생성
			timeRange.put("start", start);
			timeRange.put("end", end);
			timeRange.put("step", step);
		}
		return timeRange;

	}
	private Date UnixToDate(String unixtime) {
		if (unixtime == null || unixtime.isEmpty()) {
			return null; // 입력이 없으면 null 반환
		}

		try {
			long epochSeconds = Long.parseLong(unixtime);
			Instant instant = Instant.ofEpochSecond(epochSeconds);
			return Date.from(instant);
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException("Invalid Unix timestamp: " + unixtime, e);
		}
	}
}
