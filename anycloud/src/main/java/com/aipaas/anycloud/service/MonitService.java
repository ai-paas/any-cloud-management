package com.aipaas.anycloud.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

/**
 * <pre>
 * ClassName : MonitService
 * Type : interface
 * Description : 모니터링 관련 함수를 정리한 인터페이스입니다.
 * Related : MonitController, MonitServiceImpl
 * </pre>
 */
@Component
public interface MonitService {

	JsonNode query(
		String clusterName,
		String query,
		String time,
		String timeout,
		Integer limit,
		String lookbackDelta,
		String stats
	);
	JsonNode queryRange(
		String clusterName,
		String query,
		String start,
		String end,
		String step,
		String timeout,
		Integer limit,
		String lookbackDelta,
		String stats
	);
}
