package com.aipaas.anycloud.controller;

import com.aipaas.anycloud.model.dto.request.CreateClusterDto;
import com.aipaas.anycloud.model.entity.ClusterEntity;
import com.aipaas.anycloud.service.ClusterService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/system")
@Tag(name = "Cluster", description = "Cluster API Document")
public class ClusterController {

	private final ClusterService clusterService;

	/**
	 * [ClusterController] 클러스터 목록 조회 함수
	 *
	 * @return 클러스터 전체 목록을 반환합니다.
	 * <p>
	 */
	@GetMapping("/clusters")
	@Operation(summary = "클러스터 목록 조회", description = "클러스터 전체 목록을 조회합니다.")
	public ResponseEntity<List<ClusterEntity>> getClusters() {
		return new ResponseEntity<>(clusterService.getClusters(),
			new HttpHeaders(),
			HttpStatus.OK);
	}

	/**
	 * [ClusterController] 클러스터 단일 조회 함수
	 *
	 * @param clusterName 클러스터 이름
	 * @return 클러스터 정보를 반환합니다.
	 * <p>
	 */
	@GetMapping("/cluster/{cluster_name}")
	@Operation(summary = "클러스터 조회", description = "클러스터를 조회합니다.")
	public ResponseEntity<ClusterEntity> getCluster(
		@PathVariable("cluster_name") String clusterName) {
		return new ResponseEntity<>(clusterService.getCluster(clusterName),
			new HttpHeaders(),
			HttpStatus.OK);
	}

	/**
	 * [ClusterController] 클러스터 생성 함수
	 *
	 * @param cluster 클러스터 생성 정보
	 * @return 클러스터를 생성합니다.
	 * <p>
	 */
	@PostMapping("/cluster")
	@Operation(summary = "클러스터 생성", description = "클러스터를 생성합니다.")
	public ResponseEntity<HttpStatus> createCluster(@Valid @RequestBody CreateClusterDto cluster) {
		return new ResponseEntity<>(clusterService.createCluster(cluster),
			new HttpHeaders(),
			HttpStatus.OK);
	}

	/**
	 * [ClusterController] 클러스터 삭제 함수
	 *
	 * @param clusterName 클러스터 이름
	 * @return 클러스터 삭제합니다.
	 * <p>
	 */
	@DeleteMapping("/cluster/{cluster_name}")
	@Operation(summary = "패키지 삭제", description = "패키지를 삭제합니다.")
	public ResponseEntity<HttpStatus> deletePackage(
		@PathVariable("cluster_name") String clusterName) {
		return new ResponseEntity<>(clusterService.deleteCluster(clusterName), new HttpHeaders(),
			HttpStatus.OK);
	}

	/**
	 * [ClusterController] 클러스터 등록 여부 확인 함수
	 *
	 * @param clusterName 클러스터 이름
	 * @return 클러스터 등록 정보를 확인합니다.
	 * <p>
	 */
	@GetMapping("/cluster/exists")
	@Operation(summary = "클러스터 조회", description = "클러스터를 조회합니다.")
	public ResponseEntity<Boolean> isClusterExist(
		@Parameter(name = "clusterId", description = "조회할 클러스터 아이디",
			required = true, in = ParameterIn.QUERY) String clusterName) {
		return new ResponseEntity<>(clusterService.isClusterExist(clusterName),
			new HttpHeaders(),
			HttpStatus.OK);
	}
}
