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
import org.bouncycastle.asn1.ASN1Sequence;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.util.io.pem.PemObject;
import org.bouncycastle.util.io.pem.PemReader;
import org.springframework.context.annotation.Configuration;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
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
			// String kubeconfigContent = createKubeconfigContent(cluster);
			// log.info("Created kubeconfig content");
			Config config = buildConfig(cluster);
			client = new KubernetesClientBuilder()
				.withConfig(config)
				.build();
			// kubeconfig 내용을 사용하여 클라이언트 생성
			// client = new KubernetesClientBuilder()
			// 	.withConfig(Config.fromKubeconfig(kubeconfigContent))
			// 	.build();
			
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


	/**
	 * kubeconfig 내용을 직접 생성하여 반환
	 */
	public static String createKubeconfigContent(ClusterEntity cluster) throws Exception {
		log.info("Creating kubeconfig content for cluster: {}", cluster.getId());
		
		StringBuilder kubeconfig = new StringBuilder();
		kubeconfig.append("apiVersion: v1\n");
		kubeconfig.append("kind: Config\n");
		kubeconfig.append("clusters:\n");
		kubeconfig.append("- cluster:\n");
		// kubeconfig.append("    certificate-authority-data: ").append(cluster.getServerCa()).append("\n");
		kubeconfig.append("    insecure-skip-tls-verify: ").append("true").append("\n");
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
			// log.info("Using certificate-based authentication");
			configBuilder.withClientCertData(cluster.getClientCa());
			configBuilder.withTrustCerts(true);
			log.info("key type: {}", detectKeyAlgorithm(cluster.getClientKey()));
		  String keyType = detectKeyAlgorithm(cluster.getClientKey());
			if(keyType.equalsIgnoreCase("EC")) {
			
				String pkcs8Pem = convertECKeyToPKCS8Pem(cluster.getClientKey());
				configBuilder.withClientKeyData(pkcs8Pem);
			} else {
				configBuilder.withClientKeyData(cluster.getClientKey());
			}
				configBuilder.withClientKeyAlgo(keyType);
		
		}

		return configBuilder.build();
	}
/**
     * Base64로 인코딩된 키를 받아 EC/SEC1, RSA/PKCS#1, UNKNOWN 판별
     */
		public static String detectKeyAlgorithm(String base64Key) {
			try {
					// 1️⃣ Base64 디코딩 → PEM 문자열
					String pem = new String(Base64.getDecoder().decode(base64Key));

					// 2️⃣ PemReader로 PEM 블록 반복
					try (PemReader reader = new PemReader(new StringReader(pem))) {
							PemObject obj;
							while ((obj = reader.readPemObject()) != null) {
									String type = obj.getType();

									if ("RSA PRIVATE KEY".equalsIgnoreCase(type)) return "RSA";
									if ("EC PRIVATE KEY".equalsIgnoreCase(type)) return "EC";

									// PKCS#8 형식 PRIVATE KEY
									if ("PRIVATE KEY".equalsIgnoreCase(type)) {
											ASN1Sequence seq = ASN1Sequence.getInstance(obj.getContent());
											if (seq.size() > 0) {
													if (seq.getObjectAt(0).toString().contains("1.2.840.113549")) return "RSA";
													if (seq.getObjectAt(0).toString().contains("1.2.840.10045")) return "EC";
											}
									}
							}
					}
			} catch (Exception e) {
					// 아무것도 못 찾으면 UNKNOWN
			}
			return "UNKNOWN";
	}

	public static String convertECKeyToPKCS8Pem(String base64Key) {
    try {
        // 1. Base64 → PEM 문자열
        String pem = new String(Base64.getDecoder().decode(base64Key), StandardCharsets.UTF_8);

        // 2. PEMParser로 읽기
        try (PEMParser pemParser = new PEMParser(new StringReader(pem))) {
            Object object;
            PrivateKeyInfo privateKeyInfo = null;

            while ((object = pemParser.readObject()) != null) {
                if (object instanceof PEMKeyPair) {
                    privateKeyInfo = ((PEMKeyPair) object).getPrivateKeyInfo();
                    break;
                } else if (object instanceof PrivateKeyInfo) {
                    privateKeyInfo = (PrivateKeyInfo) object;
                    break;
                }
                // 그 외 (예: ASN1ObjectIdentifier prime256v1) 은 스킵
            }

            if (privateKeyInfo == null) {
                throw new IllegalArgumentException("No EC private key found in PEM");
            }

            // 3. PKCS#8 PEM으로 다시 직렬화
            StringWriter sw = new StringWriter();
            try (JcaPEMWriter pemWriter = new JcaPEMWriter(sw)) {
                pemWriter.writeObject(privateKeyInfo);
            }
            return sw.toString();
        }

    } catch (Exception e) {
        throw new RuntimeException("EC Key conversion failed", e);
    }
}
}



