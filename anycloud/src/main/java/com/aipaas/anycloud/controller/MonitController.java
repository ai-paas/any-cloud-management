package com.aipaas.anycloud.controller;

import com.aipaas.anycloud.service.MonitService;
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
	public ResponseEntity<Object> nodeStatus(@PathVariable("cluster") String clusterName) {
		log.info("retrieve nodeStatus ...!");
		return new ResponseEntity<>(monitService.nodeStatus(clusterName), new HttpHeaders(),
			HttpStatus.OK);

	}

	@GetMapping("/resourceMonit/{cluster}/{type}/{key}")
	public ResponseEntity<Object> resourceMonit(@PathVariable("cluster") String clusterName, @PathVariable("type") String type, @PathVariable("key") String key ,@RequestParam Map<String, String> filter) {
		log.info("retrieve resourceMonit ...!");
		return new ResponseEntity<>(monitService.resourceMonit(clusterName,type,key,filter), new HttpHeaders(),
			HttpStatus.OK);

	}

}
