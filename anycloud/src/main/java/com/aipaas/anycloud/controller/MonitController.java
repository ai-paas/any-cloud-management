package com.aipaas.anycloud.controller;

import com.aipaas.anycloud.service.MonitService;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/monit")
@Tag(name = "Monitoring", description = "Monitoring API Document")
public class MonitController {

	private final MonitService monitService;

	@GetMapping("/{cluster}/query")
	@Operation(
		summary = "Prometheus instant query 조회",
		description = "Prometheus HTTP API의 /api/v1/query 를 호출합니다."
	)
	@ApiResponses(value = {
		@ApiResponse(responseCode = "200", description = "Prometheus instant query 성공"),
		@ApiResponse(responseCode = "400", description = "잘못된 query parameter"),
		@ApiResponse(responseCode = "500", description = "서버 오류")
	})
	public ResponseEntity<JsonNode> query(
		@Parameter(description = "조회할 cluster 이름", required = true, example = "openstack")
		@PathVariable("cluster") String clusterName,
		@RequestParam("query") String query,
		@RequestParam(value = "time", required = false) String time,
		@RequestParam(value = "timeout", required = false) String timeout,
		@RequestParam(value = "limit", required = false) Integer limit,
		@RequestParam(value = "lookback_delta", required = false) String lookbackDelta,
		@RequestParam(value = "stats", required = false) String stats) {
		log.info("prometheus instant query. cluster={}, query={}", clusterName, query);
		return new ResponseEntity<>(
			monitService.query(clusterName, query, time, timeout, limit, lookbackDelta, stats),
			new HttpHeaders(),
			HttpStatus.OK
		);
	}

	@GetMapping("/{cluster}/query_range")
	@Operation(
		summary = "Prometheus range query 조회",
		description = "Prometheus HTTP API의 /api/v1/query_range 를 호출합니다."
	)
	@ApiResponses(value = {
		@ApiResponse(responseCode = "200", description = "Prometheus range query 성공"),
		@ApiResponse(responseCode = "400", description = "잘못된 query parameter"),
		@ApiResponse(responseCode = "500", description = "서버 오류")
	})
	public ResponseEntity<JsonNode> queryRange(
		@Parameter(description = "조회할 cluster 이름", required = true, example = "openstack")
		@PathVariable("cluster") String clusterName,
		@RequestParam("query") String query,
		@RequestParam("start") String start,
		@RequestParam("end") String end,
		@RequestParam("step") String step,
		@RequestParam(value = "timeout", required = false) String timeout,
		@RequestParam(value = "limit", required = false) Integer limit,
		@RequestParam(value = "lookback_delta", required = false) String lookbackDelta,
		@RequestParam(value = "stats", required = false) String stats) {
		log.info("prometheus range query. cluster={}, query={}", clusterName, query);
		return new ResponseEntity<>(
			monitService.queryRange(
				clusterName,
				query,
				start,
				end,
				step,
				timeout,
				limit,
				lookbackDelta,
				stats
			),
			new HttpHeaders(),
			HttpStatus.OK
		);
	}

}
