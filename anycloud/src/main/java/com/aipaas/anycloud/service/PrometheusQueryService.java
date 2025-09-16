package com.aipaas.anycloud.service;

import com.aipaas.anycloud.error.exception.EntityNotFoundException;
import com.aipaas.anycloud.util.PrometheusMetricProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class PrometheusQueryService {

	@Autowired
	private PrometheusMetricProperties metricProperties;

	// ========== 기본 쿼리 처리 ==========

	/**
	 * 기본 resolve 메서드 (변수 맵 방식)
	 */
	public String resolve(String group, String key, Map<String, String> variables) {
		log.info("resolve -> {}:{}:{}", group, key, variables);

		// ConfigurationProperties에서 메트릭 설정 가져오기
		Map<String, Map<String, String>> metrics = metricProperties.getMetrics();

		Map<String, String> groupMap = metrics.get(group);
		if (groupMap == null) {
			throw new EntityNotFoundException("Invalid metric group: " + group);
		}

		String queryTemplate = groupMap.get(key);
		if (queryTemplate == null) {
			throw new EntityNotFoundException("Invalid metric key: " + key + " for group: " + group);
		}

		String query = queryTemplate;

		// 변수가 있으면 치환 수행
		if (variables != null) {
			for (Map.Entry<String, String> entry : variables.entrySet()) {
				String placeholder = "${" + entry.getKey() + "}";
				String replacement = (entry.getValue() != null) ? entry.getValue() : "";
				query = query.replace(placeholder, replacement);
			}

			// 빈 값 치환으로 인한 불필요한 콤마 정리
			query = cleanupCommas(query);

		}

		return query;
	}

	/**
	 * Query Parameter 방식으로 resolve (편의 메서드)
	 */
	public String resolveFromParams(String group, String key, Map<String, String> queryParams) {
		Map<String, String> variables = buildVariables(queryParams);
		return resolve(group, key, variables);
	}

	// ========== Query Parameter 처리 ==========

	/**
	 * Query Parameters를 Prometheus 변수 맵으로 변환
	 */
	private Map<String, String> buildVariables(Map<String, String> queryParams) {
		Map<String, String> variables = new HashMap<>();

		// NODE_FILTER 생성
		String nodeFilter = buildFilter(queryParams, "node");
		if (!nodeFilter.isEmpty()) {
			variables.put("NODE_FILTER", nodeFilter);
		}

		// NAMESPACE_FILTER 생성
		String namespaceFilter = buildFilter(queryParams, "namespace");
		if (!namespaceFilter.isEmpty()) {
			variables.put("NAMESPACE_FILTER", namespaceFilter);
		}

		// POD_FILTER 생성
		String podFilter = buildFilter(queryParams, "pod");
		if (!podFilter.isEmpty()) {
			variables.put("POD_FILTER", podFilter);
		}

		// INSTANCE 관련 변수들
		String instance = queryParams.get("instance");
		if (instance != null && !instance.isEmpty()) {
			String instanceFilter = buildMultiValueFilter("instance", instance);
			variables.put("INSTANCE_FILTER", instanceFilter);

			// 단일 값이면 INSTANCE_VALUE도 설정
			List<String> instances = parseMultiValue(instance);
			if (instances.size() == 1) {
				variables.put("INSTANCE_VALUE", instances.get(0));
			}
		}

		// 시간 범위
		// String durationStr = queryParams.get("duration");
		// if (durationStr != null && !durationStr.isEmpty()) {
		//
		// // 1. duration 파싱 (숫자 검증)
		// long durationInput;
		// try {
		// durationInput = Long.parseLong(durationStr);
		// } catch (NumberFormatException e) {
		// throw new IllegalArgumentException("duration 값이 숫자가 아닙니다: " + durationStr);
		// }
		//
		// // 2. duration 단위 판별 (기본: 분)
		// // 1시간 이상인데 10000 이상이면 초 단위로 간주
		// boolean isSeconds = durationInput > 10000;
		// long durationSeconds = isSeconds ? durationInput : durationInput * 60;
		//
		// // 3. 기준 시각 하나로 고정
		// long end = Instant.now().getEpochSecond();
		// long start = end - durationSeconds;
		//
		// // 4. step 계산 (20 포인트)
		// int points = 20;
		// long step = (end - start) / points;
		//
		// // 5. 사람이 읽기 쉽게 변환
		// DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd
		// HH:mm:ss")
		// .withZone(ZoneId.of("Asia/Seoul"));
		// String startHuman = formatter.format(Instant.ofEpochSecond(start));
		// String endHuman = formatter.format(Instant.ofEpochSecond(end));
		//
		// // 6. 로그 출력
		// log.info("[Prometheus Time Range]");
		// log.info("duration (입력): " + durationInput + (isSeconds ? " sec" : " min"));
		// log.info("start = " + start + " (" + startHuman + ")");
		// log.info("end = " + end + " (" + endHuman + ")");
		// log.info("step = " + step + " sec");
		//
		// // 7. 쿼리 문자열 생성
		// variables.put("start", String.valueOf(start));
		// variables.put("end", String.valueOf(end));
		// variables.put("step", String.valueOf(step));
		// }

		return variables;
	}

	/**
	 * 특정 키에 대한 Prometheus 필터 문자열 생성
	 */
	private String buildFilter(Map<String, String> queryParams, String key) {
		String value = queryParams.get(key);
		if (value == null || value.isEmpty()) {
			return "";
		}

		// 다중값 처리
		List<String> values = parseMultiValue(value);
		if (values.isEmpty()) {
			return "";
		}

		// 연산자 확인
		String operator = queryParams.getOrDefault(key + "_op", "=");

		if (values.size() == 1) {
			String singleValue = values.get(0);
			switch (operator) {
				case "=":
					return key + "=\"" + singleValue + "\"";
				case "=~":
					return key + "=~\"" + singleValue + "\"";
				case "!=":
					return key + "!=\"" + singleValue + "\"";
				case "!~":
					return key + "!~\"" + singleValue + "\"";
				default:
					return key + "=\"" + singleValue + "\"";
			}
		} else {
			// 다중값: 정규식 패턴 - 괄호 없이 단순하게
			String regex = values.stream()
					.collect(Collectors.joining("|"));

			return operator.startsWith("!") ? key + "!~\"" + regex + "\"" : key + "=~\"" + regex + "\"";
		}
	}

	/**
	 * 다중값을 파싱하는 메서드
	 */
	private List<String> parseMultiValue(String value) {
		if (value == null || value.trim().isEmpty()) {
			return Collections.emptyList();
		}

		String[] parts;
		if (value.contains(",")) {
			parts = value.split(",");
		} else if (value.contains("|")) {
			parts = value.split("\\|");
		} else if (value.contains(" ")) {
			parts = value.split("\\s+");
		} else {
			return List.of(value.trim());
		}

		return Arrays.stream(parts)
				.map(String::trim)
				.filter(s -> !s.isEmpty())
				.collect(Collectors.toList());
	}

	/**
	 * 다중값을 Prometheus 정규식으로 변환
	 */
	private String buildMultiValueFilter(String key, String value) {
		List<String> values = parseMultiValue(value);

		if (values.isEmpty()) {
			return "";
		} else if (values.size() == 1) {
			return key + "=\"" + values.get(0) + "\"";
		} else {
			String regex = values.stream()
					.map(String::trim) // 필요시 공백 제거
					.collect(Collectors.joining("|"));
			return key + "=~\"" + regex + "\"";
		}
	}

	/**
	 * 정규식 패턴 복구 (URL 인코딩되거나 손실된 문자 복원)
	 */
	private String restoreRegexPatterns(String query) {

		// %2B를 +로 복원 (URL 인코딩된 경우)
		query = query.replace("%2B", "+");

		return query;
	}

	/**
	 * 빈 값 치환으로 인한 불필요한 콤마와 빈 변수 정리
	 */
	private String cleanupCommas(String query) {
		// 1. 빈 변수 플레이스홀더를 빈 문자열로 치환 후 콤마 정리
		query = query.replaceAll("\\$\\{[^}]+\\}", "");
		// 2. 중괄호 내에서 콤마와 공백 정리
		// ", , " -> ", "
		query = query.replaceAll(",\\s*,", ",");
		// 3. 중괄호 시작 후 바로 콤마가 오는 경우 제거 "{, " -> "{"
		query = query.replaceAll("\\{\\s*,", "{");
		// 4. 중괄호 끝나기 전 콤마 제거 ", }" -> "}"
		query = query.replaceAll(",\\s*}", "}");
		return query.trim();
	}
}
