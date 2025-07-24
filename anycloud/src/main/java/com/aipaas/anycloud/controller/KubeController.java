package com.aipaas.anycloud.controller;

import com.aipaas.anycloud.service.KubeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/kubernetes")
@Tag(name = "Packages", description = "Kubernetes API Document")
public class KubeController {

	private final KubeService kubeService;

	/**
	 * [KubeController] 쿠버네티스 리소스 목록 조회 함수
	 *
	 * @return 쿠버네티스 특정 리소스 전체 목록을 반환합니다.
	 */
	@GetMapping("/{resource_type}")
	@Operation(summary = "쿠버네티스 특정 리소스 목록 조회", description = "쿠버네티스 특정 리소스 전체를 조회합니다.")
	public ResponseEntity<?> getResources(
		@PathVariable("resource_type") String kind,
		@RequestParam(required = true) String clusterName,
		@RequestParam(required = false) String namespace
	) {
		return new ResponseEntity<>(kubeService.getResources(clusterName, namespace, kind),
			new HttpHeaders(),
			HttpStatus.OK);
	}

	/**
	 * [KubeController] 쿠버네티스 리소스 단일 조회 함수
	 *
	 * @return 쿠버네티스 특정 리소스 단일 조회합니다.
	 */
	@GetMapping("/{resource_type}/{resource_name}")
	@Operation(summary = "쿠버네티스 특정 리소스 목록 조회", description = "쿠버네티스 특정 리소스 전체를 조회합니다.")
	public ResponseEntity<?> getResource(
		@PathVariable("resource_type") String kind,
		@PathVariable("resource_name") String name,
		@RequestParam(required = true) String clusterName,
		@RequestParam(required = false) String namespace
	) {
		return new ResponseEntity<>(kubeService.getResource(clusterName, namespace, kind, name),
			new HttpHeaders(),
			HttpStatus.OK);
	}

	/**
	 * [KubeController] 쿠버네티스 리소스 삭제 함수
	 *
	 * @return 쿠버네티스 특정 리소스를 삭제합니다.
	 */
	@DeleteMapping("/{resource_type}/{resource_name}")
	@Operation(summary = "쿠버네티스 특정 리소스 삭제", description = "쿠버네티스 특정 리소스를 삭제합니다.")
	public ResponseEntity<?> deleteResources(
		@PathVariable("resource_type") String kind,
		@PathVariable("resource_name") String name,
		@RequestParam(required = true) String clusterName,
		@RequestParam(required = false) String namespace) {
		return new ResponseEntity<>(kubeService.deleteResource(clusterName, namespace, kind, name),
			new HttpHeaders(), HttpStatus.OK);
	}
}
