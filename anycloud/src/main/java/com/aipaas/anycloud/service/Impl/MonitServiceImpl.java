package com.aipaas.anycloud.service.Impl;

import com.aipaas.anycloud.model.entity.MonitEntity;
import com.aipaas.anycloud.service.MonitService;
import com.aipaas.anycloud.util.Monitoring;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

@Component
@Service("MonitServiceImpl")
@Slf4j
@RequiredArgsConstructor
public class MonitServiceImpl implements MonitService {

	private final ObjectMapper objectMapper;
	@Value("${com.innogrid.rndplan.medge.monitoringUrl}")
	private String monitoringUrl;

	@Override
	public MonitEntity realTimeMonit(String clusterName) {
		String cpuUsedQuery = Monitoring.executeClusterMetricQuery(clusterName, "cpu_used");
		String memoryUsedQuery = Monitoring.executeClusterMetricQuery(clusterName, "memory_used");
		String diskUsedQuery = Monitoring.executeClusterMetricQuery(clusterName, "disk_used");
		String cpuTotalQuery = Monitoring.executeClusterMetricQuery(clusterName, "cpu_total");
		String memoryTotalQuery = Monitoring.executeClusterMetricQuery(clusterName, "memory_total");
		String diskTotalQuery = Monitoring.executeClusterMetricQuery(clusterName, "disk_total");
		String cpuUtilQuery = Monitoring.executeClusterMetricQuery(clusterName, "cpu_util");
		String memoryUtilQuery = Monitoring.executeClusterMetricQuery(clusterName, "memory_util");
		String diskUtilQuery = Monitoring.executeClusterMetricQuery(clusterName, "disk_util");
		return MonitEntity.builder()
			.cpu(MonitEntity.RealTimeMonit.builder().total(executeQuery(cpuTotalQuery).get("value"))
				.used(executeQuery(cpuUsedQuery).get("value"))
				.util(executeQuery(cpuUtilQuery).get("value")).build())
			.memory(MonitEntity.RealTimeMonit.builder()
				.total(executeQuery(memoryTotalQuery).get("value"))
				.used(executeQuery(memoryUsedQuery).get("value"))
				.util(executeQuery(memoryUtilQuery).get("value")).build())
			.disk(
				MonitEntity.RealTimeMonit.builder().total(executeQuery(diskTotalQuery).get("value"))
					.used(executeQuery(diskUsedQuery).get("value"))
					.util(executeQuery(diskUtilQuery).get("value")).build())
			.build();
	}

	public Map<String, Double> executeQuery(String query) {
		try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
			String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);

			UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(monitoringUrl)
				.path("/api/v1/query")
				.queryParam("query", ""); // 인코딩된 쿼리를 넣어줍니다

			String apiUrl = builder.toUriString() + encodedQuery;
			HttpGet httpGet = new HttpGet(apiUrl);

			log.error("httpGet : {} ", httpGet);

			// 요청 전송 및 응답 수신
			CloseableHttpResponse response = httpClient.execute(httpGet);
			log.error("response : {} ", response);
			// 응답 처리
			String responseBody = EntityUtils.toString(
				response.getEntity());
			JsonNode jsonNode = objectMapper.readTree(responseBody);
			JsonNode valueNode = jsonNode
				.get("data")
				.get("result")
				.get(0)
				.get("value");

			return Map.of(
				"time", valueNode.get(0).asDouble(),
				"value", valueNode.get(1).asDouble());

		} catch (Exception ex) {
			log.error("log error : {}", ex.getMessage());
			throw new IllegalArgumentException("Invalid URI for uri: " + ex.getMessage(), ex);
		}
	}

}
