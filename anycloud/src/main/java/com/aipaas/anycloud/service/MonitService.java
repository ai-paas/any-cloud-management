package com.aipaas.anycloud.service;

import com.aipaas.anycloud.model.entity.MonitEntity;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * <pre>
 * ClassName : MonitService
 * Type : interface
 * Description : 모니터링 관련 함수를 정리한 인터페이스입니다.
 * Related : MonitController, MonitServiceImpl
 * </pre>
 */
@Component
public interface MonitService {

	List<MonitEntity.NodeStatus> nodeStatus(String clusterName);
	Object resourceMonit (String clusterName, String type, String key, Map<String, String> filter);
	//Object executeQuery(String url, String query);

//	String getMonitUrl(String ClusterName);
//	MonitEntity realTimeMonit(String ClusterName, Map<String, String> filter);
	// MonitEntity clusterQuery(String ClusterName);

}
