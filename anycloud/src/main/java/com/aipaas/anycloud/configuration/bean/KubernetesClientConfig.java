package com.aipaas.anycloud.configuration.bean;

import com.aipaas.anycloud.model.entity.ClusterEntity;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.ConfigBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.KubernetesClientTimeoutException;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import java.security.Security;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.springframework.context.annotation.Configuration;

import java.io.StringReader;
import java.security.PrivateKey;
import java.security.Security;
import java.util.Base64;

@Getter
@Configuration
@RequiredArgsConstructor
@Slf4j
public class KubernetesClientConfig {

	private KubernetesClient client;

	@PostConstruct
	public void registerBouncyCastle() {
		if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
			Security.addProvider(new BouncyCastleProvider());
			log.info("✅ BouncyCastle Provider 등록 완료");
		}
	}

	public KubernetesClientConfig(ClusterEntity cluster) {
		log.info("KubernetesClientConfig init");
		log.info("apiServerUrl : {}", cluster.getApiServerUrl());

		try {
			// kubeconfig 내용을 직접 생성하여 사용
			String kubeconfigContent = createKubeconfigContent(cluster);
			log.info("Created kubeconfig content");
			
			// kubeconfig 내용을 사용하여 클라이언트 생성
			client = new KubernetesClientBuilder()
				.withConfig(Config.fromKubeconfig(kubeconfigContent))
				.build();
			
			log.info("Successfully created Kubernetes client");
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

	public void closeClient() {
		if (client != null) {
			client.close();
		}
	}

	private Config buildConfig(ClusterEntity cluster) {
		log.info("Building config for cluster: {}", cluster.getId());
		log.info("API Server URL: {}", cluster.getApiServerUrl());
		log.info("Client Token length: {}", cluster.getClientToken() != null ? cluster.getClientToken().length() : 0);
		log.info("Client CA length: {}", cluster.getClientCa() != null ? cluster.getClientCa().length() : 0);
		log.info("Client Key length: {}", cluster.getClientKey() != null ? cluster.getClientKey().length() : 0);
		
		ConfigBuilder configBuilder = new ConfigBuilder()
			.withMasterUrl(cluster.getApiServerUrl())
			.withCaCertData(cluster.getServerCa());

		if (cluster.getClientToken() != null && !cluster.getClientToken().isBlank()) {
			log.info("Using token-based authentication");
			configBuilder.withOauthToken(cluster.getClientToken());
		} else {
			log.info("Using certificate-based authentication");
			configBuilder.withClientCertData(cluster.getClientCa());
			
			try {
				// RSA 키를 PKCS#8 형식으로 변환
				String pkcs8Key = convertRsaPrivateKeyToPkcs8(cluster.getClientKey());
				configBuilder.withClientKeyData(pkcs8Key);
				log.info("Successfully converted RSA private key to PKCS#8");
			} catch (Exception e) {
				log.error("Failed to convert RSA private key: {}", e.getMessage(), e);
				// 변환 실패 시 원본 키 사용
				configBuilder.withClientKeyData(cluster.getClientKey());
				log.warn("Using original key format as fallback");
			}
		}

		return configBuilder.build();
	}

	/**
	 * kubeconfig 내용을 직접 생성하여 반환
	 */
	private String createKubeconfigContent(ClusterEntity cluster) throws Exception {
		log.info("Creating kubeconfig content for cluster: {}", cluster.getId());
		
		StringBuilder kubeconfig = new StringBuilder();
		kubeconfig.append("apiVersion: v1\n");
		kubeconfig.append("kind: Config\n");
		kubeconfig.append("clusters:\n");
		kubeconfig.append("- cluster:\n");
		kubeconfig.append("    certificate-authority-data: ").append(cluster.getServerCa()).append("\n");
		kubeconfig.append("    server: ").append(cluster.getApiServerUrl()).append("\n");
		kubeconfig.append("  name: ").append(cluster.getId()).append("\n");
		kubeconfig.append("contexts:\n");
		kubeconfig.append("- context:\n");
		kubeconfig.append("    cluster: ").append(cluster.getId()).append("\n");
		kubeconfig.append("    user: ").append(cluster.getId()).append("-user\n");
		kubeconfig.append("  name: ").append(cluster.getId()).append("-context\n");
		kubeconfig.append("current-context: ").append(cluster.getId()).append("-context\n");
		kubeconfig.append("users:\n");
		kubeconfig.append("- name: ").append(cluster.getId()).append("-user\n");
		kubeconfig.append("  user:\n");
		
		if (cluster.getClientToken() != null && !cluster.getClientToken().isBlank()) {
			log.info("Using token-based authentication");
			kubeconfig.append("    token: ").append(cluster.getClientToken()).append("\n");
		} else {
			log.info("Using certificate-based authentication");
			kubeconfig.append("    client-certificate-data: ").append(cluster.getClientCa()).append("\n");
			kubeconfig.append("    client-key-data: ").append(cluster.getClientKey()).append("\n");
		}
		
		log.info("Kubeconfig content created successfully");
		return kubeconfig.toString();
	}

	/**
	 * RSA PRIVATE KEY (.kube/config client-key-data Base64) → PKCS#8 Base64
	 */
	private String convertRsaPrivateKeyToPkcs8(String base64Key) {
		try {
			log.info("Converting RSA private key to PKCS#8 format");
			
			// Base64 디코딩
			byte[] derBytes = Base64.getDecoder().decode(base64Key);
			log.info("Decoded DER bytes length: {}", derBytes.length);

			// PEMParser는 PEM 텍스트 필요 → "-----BEGIN RSA PRIVATE KEY-----" wrapping
			String pem = wrapDerToPem(derBytes, "RSA PRIVATE KEY");
			log.info("Wrapped PEM format");

			try (PEMParser parser = new PEMParser(new StringReader(pem))) {
				Object obj = parser.readObject();
				log.info("Parsed PEM object type: {}", obj.getClass().getSimpleName());
				
				PrivateKeyInfo keyInfo;

				if (obj instanceof PrivateKeyInfo) {
					keyInfo = (PrivateKeyInfo) obj;
					log.info("Object is already PrivateKeyInfo");
				} else if (obj instanceof PEMKeyPair) {
					PEMKeyPair keyPair = (PEMKeyPair) obj;
					keyInfo = keyPair.getPrivateKeyInfo();
					log.info("Extracted PrivateKeyInfo from PEMKeyPair");
				} else {
					keyInfo = PrivateKeyInfo.getInstance(obj);
					log.info("Converted object to PrivateKeyInfo");
				}

				PrivateKey privateKey = new JcaPEMKeyConverter()
					.setProvider(BouncyCastleProvider.PROVIDER_NAME)
					.getPrivateKey(keyInfo);
				log.info("Converted to PrivateKey successfully");

				// PKCS#8로 변환 후 Base64
				String pkcs8Key = Base64.getEncoder().encodeToString(privateKey.getEncoded());
				log.info("Successfully converted to PKCS#8 format");
				return pkcs8Key;
			}
		} catch (Exception e) {
			log.error("RSA clientKey 변환 실패: {}", e.getMessage(), e);
			throw new RuntimeException("Failed to convert RSA private key", e);
		}
	}

	private String wrapDerToPem(byte[] derBytes, String type) {
		String base64 = Base64.getEncoder().encodeToString(derBytes);
		StringBuilder pem = new StringBuilder();
		pem.append("-----BEGIN ").append(type).append("-----\n");

		int index = 0;
		while (index < base64.length()) {
			int end = Math.min(index + 64, base64.length());
			pem.append(base64, index, end).append("\n");
			index = end;
		}

		pem.append("-----END ").append(type).append("-----\n");
		return pem.toString();
	}

}

