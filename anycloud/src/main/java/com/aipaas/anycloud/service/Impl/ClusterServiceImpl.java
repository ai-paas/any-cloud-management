package com.aipaas.anycloud.service.Impl;

import com.aipaas.anycloud.configuration.bean.KubernetesClientConfig;
import com.aipaas.anycloud.error.enums.ErrorCode;
import com.aipaas.anycloud.error.exception.CustomException;
import com.aipaas.anycloud.error.exception.EntityNotFoundException;
import com.aipaas.anycloud.model.dto.request.CreateClusterDto;
import com.aipaas.anycloud.model.dto.request.UpdateClusterDto;
import com.aipaas.anycloud.model.entity.ClusterEntity;
import com.aipaas.anycloud.repository.ClusterRepository;
import com.aipaas.anycloud.service.ClusterService;
import io.fabric8.kubernetes.client.KubernetesClient;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Async;
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

		// 중복 체크: 같은 이름의 클러스터가 이미 존재하는지 확인
		if (clusterRepository.findByName(cluster.getClusterName()).isPresent()) {
			throw new CustomException(ErrorCode.DUPLICATE);
		}

		ClusterEntity clusterEntity = ClusterEntity.builder()
				.id(cluster.getClusterName())
				.description(cluster.getDescription())
				.status("UNKNOWN") // 초기 상태
				.version(null) // 나중에 업데이트
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
			// 먼저 클러스터를 저장
			clusterRepository.save(clusterEntity);
			log.info("Cluster {} saved successfully, initiating background status update", clusterEntity.getId());

			// 비동기적으로 Kubernetes 버전 조회 및 상태 업데이트
			updateClusterVersionAndStatusAsync(clusterEntity);

		} catch (DataIntegrityViolationException e) {
			throw new CustomException(ErrorCode.DATA_INTEGRITY);
		}
		return HttpStatus.CREATED;
	}

	/**
	 * [ClusterServiceImpl] 클러스터 업데이트 함수 (개선된 버전)
	 *
	 * @param clusterName 업데이트할 클러스터 이름
	 * @param updateDto   업데이트할 클러스터 정보
	 * @return 업데이트 결과
	 */
	public HttpStatus updateCluster(String clusterName, UpdateClusterDto updateDto) {
		log.info("Starting cluster update for: {}", clusterName);

		// 1. 클러스터 존재 확인
		ClusterEntity clusterEntity = clusterRepository.findByName(clusterName).orElseThrow(
				() -> new EntityNotFoundException("Cluster with Name " + clusterName + " Not Found."));

		log.info("Found cluster: {} (Status: {}, Version: {})",
				clusterEntity.getId(), clusterEntity.getStatus(), clusterEntity.getVersion());

		// 2. 업데이트 전 백업 (롤백용)
		ClusterEntity originalCluster = createBackup(clusterEntity);

		try {
			// 3. 부분 업데이트 수행
			updateClusterFields(clusterEntity, updateDto);
			log.info("Updated cluster fields for: {}", clusterName);

			// 4. 데이터베이스에 저장
			clusterRepository.save(clusterEntity);
			log.info("Successfully saved updated cluster: {}", clusterName);

			// 5. 연결 정보가 변경된 경우 연결 테스트 수행
			if (isConnectionInfoChanged(originalCluster, clusterEntity)) {
				log.info("Connection info changed, performing connection test for: {}", clusterName);
				updateClusterVersionAndStatusAsync(clusterEntity);
			}

			log.info("Cluster update completed successfully for: {}", clusterName);
			return HttpStatus.OK;

		} catch (Exception e) {
			log.error("Failed to update cluster {}: {}", clusterName, e.getMessage(), e);

			// 롤백 수행
			try {
				rollbackCluster(clusterEntity, originalCluster);
				log.info("Successfully rolled back cluster: {}", clusterName);
			} catch (Exception rollbackException) {
				log.error("Failed to rollback cluster {}: {}", clusterName, rollbackException.getMessage(), rollbackException);
			}

			throw new CustomException("Failed to update cluster: " + e.getMessage(), ErrorCode.INTERNAL_SERVER_ERROR);
		}
	}

	/**
	 * 클러스터 필드 부분 업데이트
	 */
	private void updateClusterFields(ClusterEntity clusterEntity, UpdateClusterDto updateDto) {
		if (updateDto.getDescription() != null) {
			clusterEntity.setDescription(updateDto.getDescription());
			log.debug("Updated description for cluster: {}", clusterEntity.getId());
		}

		if (updateDto.getClusterType() != null) {
			clusterEntity.setClusterType(updateDto.getClusterType());
			log.debug("Updated cluster type for cluster: {}", clusterEntity.getId());
		}

		if (updateDto.getClusterProvider() != null) {
			clusterEntity.setClusterProvider(updateDto.getClusterProvider());
			log.debug("Updated cluster provider for cluster: {}", clusterEntity.getId());
		}

		if (updateDto.getApiServerUrl() != null) {
			clusterEntity.setApiServerUrl(updateDto.getApiServerUrl());
			log.debug("Updated API server URL for cluster: {}", clusterEntity.getId());
		}

		if (updateDto.getApiServerIp() != null) {
			clusterEntity.setApiServerIp(updateDto.getApiServerIp());
			log.debug("Updated API server IP for cluster: {}", clusterEntity.getId());
		}

		if (updateDto.getServerCa() != null) {
			clusterEntity.setServerCa(updateDto.getServerCa());
			log.debug("Updated server CA for cluster: {}", clusterEntity.getId());
		}

		if (updateDto.getClientCa() != null) {
			clusterEntity.setClientCa(updateDto.getClientCa());
			log.debug("Updated client CA for cluster: {}", clusterEntity.getId());
		}

		if (updateDto.getClientKey() != null) {
			clusterEntity.setClientKey(updateDto.getClientKey());
			log.debug("Updated client key for cluster: {}", clusterEntity.getId());
		}

		if (updateDto.getMonitServerUrl() != null) {
			clusterEntity.setMonitServerUrl(updateDto.getMonitServerUrl());
			log.debug("Updated monitoring server URL for cluster: {}", clusterEntity.getId());
		}
	}

	/**
	 * 연결 정보가 변경되었는지 확인
	 */
	private boolean isConnectionInfoChanged(ClusterEntity original, ClusterEntity updated) {
		return !Objects.equals(original.getApiServerUrl(), updated.getApiServerUrl()) ||
				!Objects.equals(original.getServerCa(), updated.getServerCa()) ||
				!Objects.equals(original.getClientCa(), updated.getClientCa()) ||
				!Objects.equals(original.getClientKey(), updated.getClientKey()) ||
				!Objects.equals(original.getClientToken(), updated.getClientToken());
	}

	/**
	 * 클러스터 백업 생성
	 */
	private ClusterEntity createBackup(ClusterEntity original) {
		ClusterEntity backup = new ClusterEntity();
		backup.setId(original.getId());
		backup.setDescription(original.getDescription());
		backup.setStatus(original.getStatus());
		backup.setVersion(original.getVersion());
		backup.setApiServerUrl(original.getApiServerUrl());
		backup.setApiServerIp(original.getApiServerIp());
		backup.setServerCa(original.getServerCa());
		backup.setClientCa(original.getClientCa());
		backup.setClientKey(original.getClientKey());
		backup.setClientToken(original.getClientToken());
		backup.setMonitServerUrl(original.getMonitServerUrl());
		backup.setClusterType(original.getClusterType());
		backup.setClusterProvider(original.getClusterProvider());
		backup.setCreatedAt(original.getCreatedAt());
		backup.setUpdatedAt(original.getUpdatedAt());
		return backup;
	}

	/**
	 * 클러스터 롤백
	 */
	private void rollbackCluster(ClusterEntity current, ClusterEntity original) {
		current.setDescription(original.getDescription());
		current.setStatus(original.getStatus());
		current.setVersion(original.getVersion());
		current.setApiServerUrl(original.getApiServerUrl());
		current.setApiServerIp(original.getApiServerIp());
		current.setServerCa(original.getServerCa());
		current.setClientCa(original.getClientCa());
		current.setClientKey(original.getClientKey());
		current.setClientToken(original.getClientToken());
		current.setMonitServerUrl(original.getMonitServerUrl());
		current.setClusterType(original.getClusterType());
		current.setClusterProvider(original.getClusterProvider());
		clusterRepository.save(current);
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
	 * @return 쿠버네티스 클러스터가 존재하는지 확인합니다.
	 */
	public Boolean isClusterExist(String clusterName) {
		return clusterRepository.findByName(clusterName).isPresent();
	}

	/**
	 * [ClusterServiceImpl] 클러스터 연결 테스트 함수
	 *
	 * @param clusterName 테스트할 클러스터 이름
	 * @return 연결 테스트 결과
	 */
	public Boolean testClusterConnection(String clusterName) {
		log.info("Testing connection for cluster: {}", clusterName);

		try {
			ClusterEntity cluster = clusterRepository.findByName(clusterName).orElseThrow(
					() -> new EntityNotFoundException("Cluster with Name " + clusterName + " Not Found."));

			KubernetesClientConfig manager = new KubernetesClientConfig(cluster);
			KubernetesClient client = manager.getClient();

			try {
				// 실제 API 호출로 연결 테스트
				client.namespaces().list();
				client.nodes().list();

				log.info("Connection test successful for cluster: {}", clusterName);
				return true;
			} catch (Exception e) {
				log.warn("Connection test failed for cluster {}: {}", clusterName, e.getMessage());
				return false;
			} finally {
				manager.closeClient();
			}
		} catch (EntityNotFoundException e) {
			log.warn("Cluster not found for connection test: {}", clusterName);
			throw e;
		} catch (Exception e) {
			log.error("Unexpected error during connection test for cluster {}: {}", clusterName, e.getMessage(), e);
			return false;
		}
	}

	/**
	 * [ClusterServiceImpl] 클러스터 상태 강제 업데이트 함수
	 *
	 * @param clusterName 업데이트할 클러스터 이름
	 * @return 업데이트 결과
	 */
	public HttpStatus refreshClusterStatus(String clusterName) {
		log.info("Starting forced status refresh for cluster: {}", clusterName);

		try {
			ClusterEntity cluster = clusterRepository.findByName(clusterName).orElseThrow(
					() -> new EntityNotFoundException("Cluster with Name " + clusterName + " Not Found."));

			// 즉시 상태 업데이트 수행
			updateClusterVersionAndStatus(cluster);

			log.info("Successfully refreshed status for cluster: {}", clusterName);
			return HttpStatus.OK;

		} catch (EntityNotFoundException e) {
			log.warn("Cluster not found for status refresh: {}", clusterName);
			throw e;
		} catch (Exception e) {
			log.error("Failed to refresh status for cluster {}: {}", clusterName, e.getMessage(), e);
			throw new CustomException("Failed to refresh cluster status: " + e.getMessage(), ErrorCode.INTERNAL_SERVER_ERROR);
		}
	}

	/**
	 * [ClusterServiceImpl] 클러스터 버전 및 상태 비동기 업데이트 함수
	 *
	 * @param clusterEntity 업데이트할 클러스터 엔티티
	 */
	@Async
	public CompletableFuture<Void> updateClusterVersionAndStatusAsync(ClusterEntity clusterEntity) {
		log.info("Starting async version and status update for cluster: {}", clusterEntity.getId());

		try {
			updateClusterVersionAndStatus(clusterEntity);
			log.info("Async version and status update completed for cluster: {}", clusterEntity.getId());
		} catch (Exception e) {
			log.error("Async version and status update failed for cluster {}: {}",
					clusterEntity.getId(), e.getMessage(), e);
		}

		return CompletableFuture.completedFuture(null);
	}

	/**
	 * [ClusterServiceImpl] 클러스터 버전 및 상태 업데이트 함수
	 *
	 * @param clusterEntity 업데이트할 클러스터 엔티티
	 */
	public void updateClusterVersionAndStatus(ClusterEntity clusterEntity) {
		log.info("Updating version and status for cluster: {}", clusterEntity.getId());

		KubernetesClientConfig manager = new KubernetesClientConfig(clusterEntity);
		KubernetesClient client = manager.getClient();

		try {
			// 클러스터 상태 확인 (간단한 API 호출)
			client.namespaces().list();

			// Kubernetes 클러스터 버전 정보 조회
			String version = "Unknown";
			try {
				// /version 엔드포인트를 통해 실제 클러스터 버전 조회
				var versionInfo = client.getKubernetesVersion();
				if (versionInfo != null && versionInfo.getGitVersion() != null) {
					version = versionInfo.getGitVersion();
				}
			} catch (Exception versionException) {
				log.warn("Failed to get Kubernetes version for cluster {}: {}",
						clusterEntity.getId(), versionException.getMessage());
				version = "Version Unknown";
			}

			// 상태와 버전 업데이트
			clusterEntity.setStatus("ACTIVE");
			clusterEntity.setVersion(version);

			clusterRepository.save(clusterEntity);
			log.info("Successfully updated cluster {} - Status: {}, Version: {}",
					clusterEntity.getId(), clusterEntity.getStatus(), clusterEntity.getVersion());

		} catch (Exception e) {
			log.error("Failed to update cluster {}: {}", clusterEntity.getId(), e.getMessage(), e);
			clusterEntity.setStatus("INACTIVE");
			clusterEntity.setVersion("UNKNOWN");
			clusterRepository.save(clusterEntity);
		} finally {
			manager.closeClient();
		}
	}

	/**
	 * [ClusterServiceImpl] 모든 클러스터의 상태 업데이트 함수
	 */
	public void updateAllClusterStatuses() {
		log.info("Starting periodic cluster status update");

		List<ClusterEntity> clusters = clusterRepository.findAll();
		for (ClusterEntity cluster : clusters) {
			try {
				updateClusterStatus(cluster);
			} catch (Exception e) {
				log.error("Failed to update status for cluster {}: {}", cluster.getId(), e.getMessage());
			}
		}

		log.info("Completed periodic cluster status update for {} clusters", clusters.size());
	}

	/**
	 * [ClusterServiceImpl] 클러스터 상태 및 버전 업데이트 함수
	 *
	 * @param clusterEntity 업데이트할 클러스터 엔티티
	 */
	public void updateClusterStatus(ClusterEntity clusterEntity) {
		log.info("Updating status and version for cluster: {}", clusterEntity.getId());

		KubernetesClientConfig manager = new KubernetesClientConfig(clusterEntity);
		KubernetesClient client = manager.getClient();

		try {
			// 간단한 API 호출로 연결 상태 확인
			client.namespaces().list();

			// Kubernetes 클러스터 버전 정보 조회
			String version = "Unknown";
			try {
				// /version 엔드포인트를 통해 실제 클러스터 버전 조회
				var versionInfo = client.getKubernetesVersion();
				if (versionInfo != null && versionInfo.getGitVersion() != null) {
					version = versionInfo.getGitVersion();
				}
			} catch (Exception versionException) {
				log.warn("Failed to get Kubernetes version for cluster {}: {}",
						clusterEntity.getId(), versionException.getMessage());
				version = "Version Unknown";
			}

			// 상태와 버전 업데이트
			clusterEntity.setStatus("ACTIVE");
			clusterEntity.setVersion(version);
			clusterRepository.save(clusterEntity);
			log.info("Cluster {} status updated to ACTIVE, version: {}", clusterEntity.getId(), version);

		} catch (Exception e) {
			log.warn("Cluster {} is not accessible: {}", clusterEntity.getId(), e.getMessage());
			clusterEntity.setStatus("INACTIVE");
			clusterEntity.setVersion("UNKNOWN");
			clusterRepository.save(clusterEntity);
		} finally {
			manager.closeClient();
		}
	}
}
