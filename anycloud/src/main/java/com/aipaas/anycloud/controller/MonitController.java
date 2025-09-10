package com.aipaas.anycloud.controller;

import com.aipaas.anycloud.service.MonitService;
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

import java.util.Map;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/monit")
@Tag(name = "Monitoring", description = "Monitoring API Document")
public class MonitController {

	private final MonitService monitService;

//	@GetMapping("/realtime/{cluster}")
//	public ResponseEntity<Object> realTimeMonit(@PathVariable("cluster") String clusterName, @RequestParam Map<String, String> filter) {
//		log.info("retrieve realtime cluster cpu, memory, disk value ...!");
//		log.info("cluster = {}", clusterName);
//		log.info("queryParams = {}", filter);
//		return new ResponseEntity<>(monitService.realTimeMonit(clusterName, filter), new HttpHeaders(),
//			HttpStatus.OK);
//
//	}

	@GetMapping("/nodeStatus/{cluster}")
	@Operation(
		summary = "대시보드 > 인프라 > 노드 상태 조회",
		description = "클러스터 내 노드별 상태 조회"
	)
	@ApiResponses(value = {
		@ApiResponse(responseCode = "200", description = "node Status 조회 성공"),
		@ApiResponse(responseCode = "400", description = "cluster 또는 모니터링 서버 url을 찾을 수 없음"),
		@ApiResponse(responseCode = "500", description = "서버 오류")
	})
	public ResponseEntity<Object> nodeStatus(
		@Parameter(description = "조회할 cluster id", required = true, example = "openstack")
		@PathVariable("cluster") String clusterName) {
		log.info("retrieve nodeStatus ...!");
		return new ResponseEntity<>(monitService.nodeStatus(clusterName), new HttpHeaders(),
			HttpStatus.OK);

	}

	@GetMapping("/resourceMonit/{cluster}/{type}/{key}")
	@Operation(
		summary = "모니터링 메트릭 조회",
		description = "대시보드 모니터링 메트릭 조회  https://github.com/ai-paas/any-cloud-management/blob/main/anycloud/src/main/resources/application.yaml 참고"
	)
	@ApiResponses(value = {
		@ApiResponse(responseCode = "200", description = "메트릭 조회 성공"),
		@ApiResponse(responseCode = "400", description = "cluster 또는 모니터링 서버 url을 찾을 수 없음"),
		@ApiResponse(responseCode = "500", description = "서버 오류")
	})
	public ResponseEntity<Object> resourceMonit(
		@Parameter(description = "조회할 cluster 이름", required = true, example = "openstack")
		@PathVariable("cluster") String clusterName,
		@Parameter(description = "메트릭 타입", required = true, example = "cpu")
		@PathVariable("type") String type,
		@Parameter(description = "조회할 메트릭 key", required = true, example = "usage_namespace")
		@PathVariable("key") String key ,
		@Parameter(description = "node,namespace filter 및 duration", required = false, example = "{\"namespace\":\"kubeflow\", \"duration\":\"3600\"}")
		@RequestParam Map<String, String> filter) {
		log.info("retrieve resourceMonit ...!");
		return new ResponseEntity<>(monitService.resourceMonit(clusterName,type,key,filter), new HttpHeaders(),
			HttpStatus.OK);

	}

}
