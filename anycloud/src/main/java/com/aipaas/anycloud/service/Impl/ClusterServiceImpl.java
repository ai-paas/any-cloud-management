package com.aipaas.anycloud.service.Impl;

import com.aipaas.anycloud.error.enums.ErrorCode;
import com.aipaas.anycloud.error.exception.CustomException;
import com.aipaas.anycloud.error.exception.EntityNotFoundException;
import com.aipaas.anycloud.model.dto.request.CreateClusterDto;
import com.aipaas.anycloud.model.entity.ClusterEntity;
import com.aipaas.anycloud.repository.ClusterRepository;
import com.aipaas.anycloud.service.ClusterService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * <pre>
 * ClassName : clusterServiceImpl
 * Type : class
 * Description : 쿠버네티스 클러스터와 관련된 서비스 구현과 관련된 함수를 포함하고 있는 클래스입니다.
 * Related : ClusterController, ClusterService
 * </pre>
 */
@Slf4j
@Service("clusterServiceImpl")
@Transactional
@RequiredArgsConstructor
public class ClusterServiceImpl implements ClusterService {

	private final ClusterRepository clusterRepository;

	/**
	 * [ClusterServiceImpl] 쿠버네티스 클러스터 전체 목록 함수
	 *
	 * @return 전체 쿠버네티스 클러스터 목록을 반환합니다.
	 */
	@Transactional(readOnly = true)
	public List<ClusterEntity> getClusters() {
		return clusterRepository.findAll();
	}

	/**
	 * [ClusterServiceImpl] 클러스터 단일 조회 함수
	 *
	 * @return 쿠버네티스 클러스터를 조회합니다.
	 */
	@Transactional(readOnly = true)
	public ClusterEntity getCluster(String clusterName) {
		return clusterRepository.findByName(clusterName).orElseThrow(
				() -> new EntityNotFoundException("Cluster with Name " + clusterName + " Not Found."));
	}

	/**
	 * [ClusterServiceImpl] 클러스터 생성 함수
	 *
	 * @return 쿠버네티스 클러스터를 등록합니다.
	 */
	public HttpStatus createCluster(CreateClusterDto cluster) {

		if (cluster == null) {
			throw new CustomException(ErrorCode.INVALID_INPUT_VALUE);
		}

		ClusterEntity clusterEntity = ClusterEntity.builder()
				.id(cluster.getClusterName())
				.description(cluster.getDescription())
				.version(null)
				.apiServerUrl(cluster.getApiServerUrl())
				.apiServerIp(cluster.getApiServerIp())
				.serverCa(cluster.getServerCA())
				.clientCa(cluster.getClientCA())
				.clientKey(cluster.getClientKey())
				.monitServerUrl(cluster.getMonitServerURL())
				.clusterType(cluster.getClusterType())
				.clusterProvider(cluster.getClusterProvider())
				.build();

		try {
			clusterRepository.save(clusterEntity);
		} catch (DataIntegrityViolationException e) {
			throw new CustomException(ErrorCode.DATA_INTEGRITY);
		}
		return HttpStatus.CREATED;
	}

	/**
	 * [ClusterServiceImpl] 클러스터 삭제 함수
	 *
	 * @return 쿠버네티스 클러스터를 삭제합니다.
	 */
	public HttpStatus deleteCluster(String clusterName) {
		clusterRepository.delete(clusterRepository.findByName(clusterName).orElseThrow(
				() -> new EntityNotFoundException("Cluster with Name " + clusterName + " Not Found.")));
		return HttpStatus.OK;
	}

	/**
	 * [ClusterServiceImpl] 클러스터 중복 확인 함수
	 *
	 * @return 쿠버네티스 클러스터를 중복 체크합니다.
	 */
	public Boolean isClusterExist(String clusterName) {
		return clusterRepository.findByName(clusterName).isEmpty();
	}
}
