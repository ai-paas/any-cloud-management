package com.aipaas.anycloud.service;

import com.aipaas.anycloud.model.entity.MonitEntity;
import org.springframework.stereotype.Component;

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

	Object executeQuery(String query);

	MonitEntity realTimeMonit(String ClusterName);
	// MonitEntity clusterQuery(String ClusterName);

}
