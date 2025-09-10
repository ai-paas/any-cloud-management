package com.aipaas.anycloud.controller;

import com.aipaas.anycloud.model.dto.request.CreateHelmRepoDto;
import com.aipaas.anycloud.model.entity.HelmRepoEntity;
import com.aipaas.anycloud.service.HelmRepoService;
import com.aipaas.anycloud.service.HelmRepoService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/helm-repos")
@Tag(name = "HelmRepository", description = "HelmRepository API Document")
public class HelmRepoController {

	private final HelmRepoService helmRepoService;

	/**
	 * [HelmRepoController] 헬름 저장소 목록 조회 함수
	 *
	 * @return 헬름 저장소 전체 목록을 반환합니다.
	 * <p>
	 */
	@GetMapping("")
	@Operation(summary = "헬름 저장소 목록 조회", description = "헬름 저장소 전체 목록을 조회합니다.")
	@ApiResponses(value = {
		@ApiResponse(responseCode = "200", description = "헬름 저장소 목록 조회 성공"),
		@ApiResponse(responseCode = "400", description = "Repository 정보를 찾을 수 없음"),
		@ApiResponse(responseCode = "500", description = "서버 오류")
	})
	public ResponseEntity<List<HelmRepoEntity>> getHelmRepos() {
		return new ResponseEntity<>(helmRepoService.getHelmRepos(),
			new HttpHeaders(),
			HttpStatus.OK);
	}

	/**
	 * [HelmRepoController] 헬름 저장소 단일 조회 함수
	 *
	 * @param helmRepoName 헬름 저장소 이름
	 * @return 헬름 저장소 정보를 반환합니다.
	 * <p>
	 */
	@GetMapping("/{helmRepoName}")
	@Operation(summary = "헬름 저장소 조회", description = "헬름 저장소를 조회합니다.")
	public ResponseEntity<HelmRepoEntity> getHelmRepo(
		@Parameter(description = "Helm repository 이름", required = true, example = "chart-museum")
		@PathVariable("helmRepoName") String helmRepoName) {
		return new ResponseEntity<>(helmRepoService.getHelmRepo(helmRepoName),
			new HttpHeaders(),
			HttpStatus.OK);
	}

	/**
	 * [HelmRepoController] 헬름 저장소 생성 함수
	 *
	 * @param cluster 헬름 저장소 생성 정보
	 * @return 헬름 저장소를 생성합니다.
	 * <p>
	 */
	@PostMapping("")
	@Operation(summary = "헬름 저장소 생성", description = "헬름 저장소를 생성합니다.")
	public ResponseEntity<HttpStatus> createHelmRepo(@Valid @RequestBody CreateHelmRepoDto cluster) {
		return new ResponseEntity<>(helmRepoService.createHelmRepo(cluster),
			new HttpHeaders(),
			HttpStatus.OK);
	}

	/**
	 * [HelmRepoController] 헬름 저장소 삭제 함수
	 *
	 * @param clusterName 헬름 저장소 이름
	 * @return 헬름 저장소 삭제합니다.
	 * <p>
	 */
	@DeleteMapping("/{helmRepoName}")
	@Operation(summary = "패키지 삭제", description = "패키지를 삭제합니다.")
	public ResponseEntity<HttpStatus> deletePackage(
		@PathVariable("helmRepoName") String clusterName) {
		return new ResponseEntity<>(helmRepoService.deleteHelmRepo(clusterName), new HttpHeaders(),
			HttpStatus.OK);
	}

	/**
	 * [HelmRepoController] 헬름 저장소 등록 여부 확인 함수
	 *
	 * @param helmRepoName 헬름 저장소 이름
	 * @return 헬름 저장소 등록 정보를 확인합니다.
	 * <p>
	 */
	@GetMapping("/{helmRepoName}/exists")
	@Operation(summary = "헬름 저장소 조회", description = "헬름 저장소를 조회합니다.")
	public ResponseEntity<Boolean> isHelmRepoExist(
		@Parameter(description = "Helm repository 이름", required = true, example = "chart-museum")
		@PathVariable("helmRepoName") String helmRepoName) {
		return new ResponseEntity<>(helmRepoService.isHelmExist(helmRepoName),
			new HttpHeaders(),
			HttpStatus.OK);
	}
}
