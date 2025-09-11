package com.aipaas.anycloud.service;

import io.fabric8.kubernetes.api.model.HasMetadata;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * <pre>
 * ClassName : KubeService
 * Type : interface
 * Description : 쿠버네티스 관련된 함수를 정리한 인터페이스입니다.
 * Related : KubeServiceImpl
 * </pre>
 */
@Component
public interface KubeService {

	List<? extends HasMetadata> getResources(String clusterName, String namespace, String kind);

	HasMetadata getResource(String clusterName, String namespace, String kind, String name);

	boolean deleteResource(String clusterName, String namespace, String kind, String name);
	
	boolean testConnection(String clusterName);
}
