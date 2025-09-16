package com.aipaas.anycloud.service;

import com.aipaas.anycloud.model.dto.request.CreateClusterDto;
import com.aipaas.anycloud.model.entity.ClusterEntity;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * <pre>
 * ClassName : ClusterService
 * Type : interface
 * Description : 쿠버네티스 클러스터와 관련된 함수를 정리한 인터페이스입니다.
 * Related : ClusterServiceImpl
 * </pre>
 */
@Component
public interface ClusterService {

	List<ClusterEntity> getClusters();

	ClusterEntity getCluster(String clusterName);

	HttpStatus createCluster(CreateClusterDto cluster);

	HttpStatus deleteCluster(String clusterName);

	Boolean isClusterExist(String clusterName);

	void updateAllClusterStatuses();
}
