package com.aipaas.anycloud.service.Impl;

import com.aipaas.anycloud.configuration.bean.KubernetesClientConfig;
import com.aipaas.anycloud.error.exception.EntityNotFoundException;
import com.aipaas.anycloud.model.entity.ClusterEntity;
import com.aipaas.anycloud.model.enums.ResourceType;
import com.aipaas.anycloud.service.ClusterService;
import com.aipaas.anycloud.service.KubeService;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.client.KubernetesClient;
import java.util.Collections;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * <pre>
 * ClassName : kubeServiceImpl
 * Type : class
 * Description : 쿠버네티스와 관련된 서비스 구현과 관련된 함수를 포함하고 있는 클래스입니다.
 * Related : kubernetes-client
 * </pre>
 */
@Slf4j
@Service("kubeServiceImpl")
@RequiredArgsConstructor
public class KubeServiceImpl implements KubeService {

	private final ClusterService clusterService;

	public List<? extends HasMetadata> getResources(String clusterName, String namespace,
			String kind) {
		// namespace가 빈값이면 "default"로 설정
		if (namespace == null || namespace.trim().isEmpty()) {
			namespace = "default";
		}
		log.info("Getting resources for cluster: {}, namespace: {}, kind: {}", clusterName, namespace, kind);

		try {
			ClusterEntity cluster = clusterService.getCluster(clusterName);
			log.info("Found cluster: {}", cluster.getId());

			KubernetesClientConfig manager = new KubernetesClientConfig(cluster);
			KubernetesClient client = manager.getClient();
			log.info("Created Kubernetes client successfully");

			try {
				ResourceType type = ResourceType.fromKind(kind);
				log.info("Found resource type: {}", type);

				List<? extends HasMetadata> resources = type.getResources(client, namespace);
				log.info("Retrieved {} resources of type {}", resources.size(), kind);

				return resources;
			} catch (Exception e) {
				log.error("Failed to fetch resources for kind [{}] in namespace [{}]: {}", kind,
						namespace, e.getMessage(), e);
				return Collections.emptyList();
			} finally {
				manager.closeClient();
			}
		} catch (EntityNotFoundException e) {
			// 클러스터를 찾을 수 없는 경우 EntityNotFoundException을 그대로 전파
			log.warn("Cluster not found: {}", clusterName);
			throw e;
		} catch (Exception e) {
			log.error("Failed to initialize Kubernetes client for cluster [{}]: {}", clusterName, e.getMessage(), e);
			return Collections.emptyList();
		}
	}

	public HasMetadata getResource(String clusterName, String namespace, String kind, String name) {
		// namespace가 빈값이면 "default"로 설정
		if (namespace == null || namespace.trim().isEmpty()) {
			namespace = "default";
		}
		
		try {
			ClusterEntity cluster = clusterService.getCluster(clusterName);
			KubernetesClientConfig manager = new KubernetesClientConfig(cluster);
			KubernetesClient client = manager.getClient();

			try {
				ResourceType type = ResourceType.fromKind(kind);
				return type.getResourceByName(client, namespace, name);
			} catch (Exception e) {
				log.error("Failed to fetch resource [{}] of kind [{}] in namespace [{}]: {}", name,
						kind, namespace, e.getMessage(), e);
				return null;
			} finally {
				manager.closeClient();
			}
		} catch (EntityNotFoundException e) {
			// 클러스터를 찾을 수 없는 경우 EntityNotFoundException을 그대로 전파
			log.warn("Cluster not found: {}", clusterName);
			throw e;
		}
	}

	public boolean deleteResource(String clusterName, String namespace, String kind, String name) {
		// namespace가 빈값이면 "default"로 설정
		if (namespace == null || namespace.trim().isEmpty()) {
			namespace = "default";
		}
		
		try {
			ClusterEntity cluster = clusterService.getCluster(clusterName);
			KubernetesClientConfig manager = new KubernetesClientConfig(cluster);
			KubernetesClient client = manager.getClient();

			try {
				ResourceType type = ResourceType.fromKind(kind);
				return type.deleteResource(client, namespace, name);
			} catch (Exception e) {
				log.error("Failed to delete [{}] resource [{}] in namespace [{}]: {}", kind, name,
						namespace,
						e.getMessage(), e);
				return false;
			} finally {
				manager.closeClient();
			}
		} catch (EntityNotFoundException e) {
			// 클러스터를 찾을 수 없는 경우 EntityNotFoundException을 그대로 전파
			log.warn("Cluster not found: {}", clusterName);
			throw e;
		}
	}

	public boolean testConnection(String clusterName) {
		log.info("Testing connection to cluster: {}", clusterName);

		try {
			// 클러스터가 존재하지 않으면 EntityNotFoundException을 그대로 전파
			ClusterEntity cluster = clusterService.getCluster(clusterName);
			log.info("Found cluster: {}", cluster.getId());

			KubernetesClientConfig manager = new KubernetesClientConfig(cluster);
			KubernetesClient client = manager.getClient();
			log.info("Created Kubernetes client successfully");

			try {
				// 실제 API 호출로 연결 테스트 - 네임스페이스 목록 조회
				var namespaces = client.namespaces().list();
				log.info("Successfully connected to cluster. Found {} namespaces", namespaces.getItems().size());

				// 추가로 노드 정보도 조회해서 더 확실한 연결 테스트
				var nodes = client.nodes().list();
				log.info("Successfully retrieved {} nodes from cluster", nodes.getItems().size());

				return true;
			} catch (Exception e) {
				log.error("Failed to connect to cluster [{}]: {}", clusterName, e.getMessage(), e);
				return false;
			} finally {
				manager.closeClient();
			}
		} catch (EntityNotFoundException e) {
			// 클러스터를 찾을 수 없는 경우 EntityNotFoundException을 그대로 전파
			log.warn("Cluster not found: {}", clusterName);
			throw e;
		}
	}
}
