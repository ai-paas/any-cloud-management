package com.aipaas.anycloud.configuration.bean;

import com.aipaas.anycloud.model.entity.ClusterEntity;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.ConfigBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.KubernetesClientTimeoutException;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;

/**
 * <pre>
 * ClassName : KubernetesClientConfig
 * Type : class
 * Description : Kubernetes 연결에 필요한 정보를 포함하고 있는 클래스입니다.
 * Related : All
 * </pre>
 */
@Getter
@Configuration
@RequiredArgsConstructor
@Slf4j
public class KubernetesClientConfig {

	private KubernetesClient client;

	public KubernetesClientConfig(ClusterEntity cluster) {

		try {
			// Kubernetes 클라이언트 초기화
			client = new KubernetesClientBuilder()
				.withConfig(buildConfig(cluster))
				.build();
		} catch (KubernetesClientTimeoutException e) {
			log.error("타임아웃 오류: {}", e.getMessage(), e);
			throw new RuntimeException("클러스터 클라이언트 생성 실패 (타임아웃)", e);
		} catch (KubernetesClientException e) {
			int code = e.getCode();
			if (code == 401) {
				log.error("인증 실패 (401 Unauthorized)");
				throw new RuntimeException("클러스터 클라이언트 생성 실패 (인증 실패)", e);
			} else if (code == 403) {
				log.error("권한 없음 (403 Forbidden)");
				throw new RuntimeException("클러스터 클라이언트 생성 실패 (권한 없음)", e);
			} else if (code == 404) {
				log.error("요청한 리소스를 찾을 수 없음 (404 Not Found)");
				throw new RuntimeException("클러스터 클라이언트 생성 실패 (리소스 찾을 수 없음)", e);
			} else {
				log.error("기타 KubernetesClientException 발생: {} - {}", code, e.getMessage(), e);
			}
			throw new RuntimeException("클러스터 클라이언트 생성 실패 (Kubernetes 오류)", e);
		} catch (Exception e) {
			log.error("예상치 못한 예외: {}", e.getMessage(), e);
			throw new RuntimeException("클러스터 클라이언트 생성 실패", e);
		}
	}

	// 클라이언트 사용이 끝난 후에는 반드시 클라이언트를 종료
	public void closeClient() {
		if (client != null) {
			client.close();
		}
	}


	private Config buildConfig(ClusterEntity cluster) {
		Config config = new ConfigBuilder()
			.withMasterUrl(cluster.getApiServerUrl())
			.withCaCertData(cluster.getServerCa()).build();

		if (!cluster.getClientToken().isBlank()) {
			config.setOauthToken(cluster.getClientToken());
			config.setClientCertData(null);
			config.setClientKeyData(null);
		} else {
			config.setOauthToken(null);
			config.setClientCertData(cluster.getClientCa());
			config.setClientKeyData(cluster.getClientKey());
		}

		return config;
	}
}
