package com.aipaas.anycloud.controller;

import com.aipaas.anycloud.service.MonitService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/monit")
@Tag(name = "Monitoring", description = "Monitoring API Document")
public class MonitController {

	private final MonitService monitService;

	@GetMapping("/realtime")
	public ResponseEntity<Object> realTimeMonit(@RequestParam("cluster") String clusterName) {
		log.info("retrieve realtime cluster cpu, memory, disk value ...!");
		return new ResponseEntity<>(monitService.realTimeMonit(clusterName), new HttpHeaders(),
			HttpStatus.OK);

	}

}
