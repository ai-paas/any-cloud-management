package com.aipaas.anycloud.service.Impl;

import com.aipaas.anycloud.configuration.bean.KubernetesClientConfig;
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
import org.springframework.transaction.annotation.Transactional;

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
@Transactional
@RequiredArgsConstructor
public class KubeServiceImpl implements KubeService {

	private final ClusterService clusterService;

	public List<? extends HasMetadata> getResources(String clusterName, String namespace,
		String kind) {
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
		} catch (Exception e) {
			log.error("Failed to initialize Kubernetes client for cluster [{}]: {}", clusterName, e.getMessage(), e);
			return Collections.emptyList();
		}
	}

	public HasMetadata getResource(String clusterName, String namespace, String kind, String name) {
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
	}

	public boolean deleteResource(String clusterName, String namespace, String kind, String name) {
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
	}

	public boolean testConnection(String clusterName) {
		log.info("Testing connection to cluster: {}", clusterName);
		
		try {
			ClusterEntity cluster = clusterService.getCluster(clusterName);
			log.info("Found cluster: {}", cluster.getId());
			
			KubernetesClientConfig manager = new KubernetesClientConfig(cluster);
			KubernetesClient client = manager.getClient();
			log.info("Created Kubernetes client successfully");

			try {
				// 간단한 API 호출로 연결 테스트
				String version = client.getApiVersion();
				log.info("Successfully connected to cluster. API version: {}", version);
				return true;
			} catch (Exception e) {
				log.error("Failed to connect to cluster [{}]: {}", clusterName, e.getMessage(), e);
				return false;
			} finally {
				manager.closeClient();
			}
		} catch (Exception e) {
			log.error("Failed to initialize Kubernetes client for cluster [{}]: {}", clusterName, e.getMessage(), e);
			return false;
		}
	}

}
