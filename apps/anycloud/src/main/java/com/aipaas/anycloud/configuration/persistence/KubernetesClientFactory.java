package com.aipaas.anycloud.configuration.persistence;

import com.aipaas.anycloud.domain.cluster.ClusterEntity;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.security.Security;
import java.util.Base64;
import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.asn1.ASN1Sequence;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.util.io.pem.PemObject;
import org.bouncycastle.util.io.pem.PemReader;

/**
 * {@link ClusterEntity} 로부터 Fabric8 {@link KubernetesClient} 를 생성하는 정적 팩토리.
 * <p>
 * 클라이언트 인스턴스 자체는 {@code AgentBootstrapKubeClient} 가 bootstrap 경로에서 보관/재사용
 * 한다 (day-2 는 agent gRPC). 본 팩토리는
 * 매번 새 인스턴스를 만들 수 있는 빌더 헬퍼만 제공한다(파싱·키 변환 비용 포함).
 * <p>
 * BouncyCastle Provider 는 클래스 로딩 시점에 1회 등록된다.
 */
@Slf4j
public final class KubernetesClientFactory {

    static {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
            log.info("BouncyCastle Provider registered (static init)");
        }
    }

    private KubernetesClientFactory() {}

    /**
     * {@link ClusterEntity} 로부터 새 {@link KubernetesClient} 를 생성한다.
     * 호출부는 일반적으로 본 메서드 대신 캐시 컴포넌트를 통해 인스턴스를 얻어야 한다.
     */
    public static KubernetesClient buildClient(ClusterEntity cluster) {
        log.debug("Building KubernetesClient for cluster: {}", cluster.getId());
        Config config = buildConfig(cluster);
        return new KubernetesClientBuilder().withConfig(config).build();
    }

    // createKubeconfigContent 제거됨. kubeconfig YAML 생성은 cluster-agent 의 GENERATE_KUBECONFIG
    // RPC (KubeconfigExportService) 로 이관 — backend 는 admin 자격을 보관하지 않는다.

    /**
     * ClusterEntity 의 apiServerUrl/serverCa/clientCa/Key/Token 컬럼 제거 후
     * 본 factory 는 더 이상 사용 불가. backend 가 cluster 의 K8s API 를 직접 칠 일이 없어 — 모든
     * 호출은 cluster-agent RPC 로 대체. Compile 통과를 위해 stub 으로 유지; 호출 시 명확한 에러.
     * 다음 batch 에서 본 클래스 호출처 모두 제거 후 file 자체 삭제 예정.
     */
    private static Config buildConfig(ClusterEntity cluster) {
        throw new UnsupportedOperationException(
                "KubernetesClientFactory deprecated: backend 는 더 이상 cluster K8s API 를 "
                        + "직접 호출하지 않음. cluster-agent RPC (LIST_RESOURCES, APPLY_MANIFEST 등) 사용 권장. "
                        + "cluster=" + (cluster == null ? "null" : cluster.getId()));
    }

    /**
     * Base64 로 인코딩된 키를 받아 알고리즘 종류(EC/RSA/UNKNOWN)를 판별한다.
     */
    public static String detectKeyAlgorithm(String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            return "UNKNOWN";
        }
        try {
            String pem = new String(Base64.getDecoder().decode(base64Key));
            try (PemReader reader = new PemReader(new StringReader(pem))) {
                PemObject obj;
                while ((obj = reader.readPemObject()) != null) {
                    String type = obj.getType();
                    if ("RSA PRIVATE KEY".equalsIgnoreCase(type)) return "RSA";
                    if ("EC PRIVATE KEY".equalsIgnoreCase(type)) return "EC";
                    if ("PRIVATE KEY".equalsIgnoreCase(type)) {
                        ASN1Sequence seq = ASN1Sequence.getInstance(obj.getContent());
                        if (seq.size() > 0) {
                            String oid = seq.getObjectAt(0).toString();
                            if (oid.contains("1.2.840.113549")) return "RSA";
                            if (oid.contains("1.2.840.10045")) return "EC";
                        }
                    }
                }
            }
        } catch (Exception e) {
            // silent swallow 금지. UNKNOWN fall-through 는 유지하되 운영자가 추적 가능.
            log.debug("detectKeyAlgorithm: PEM 파싱 실패 — fallback to UNKNOWN", e);
        }
        return "UNKNOWN";
    }

    /**
     * SEC1 형식 EC private key 를 PKCS#8 PEM 으로 변환한다.
     */
    public static String convertECKeyToPKCS8Pem(String base64Key) {
        try {
            String pem = new String(Base64.getDecoder().decode(base64Key), StandardCharsets.UTF_8);
            try (PEMParser pemParser = new PEMParser(new StringReader(pem))) {
                Object object;
                PrivateKeyInfo privateKeyInfo = null;
                while ((object = pemParser.readObject()) != null) {
                    if (object instanceof PEMKeyPair pair) {
                        privateKeyInfo = pair.getPrivateKeyInfo();
                        break;
                    }
                    if (object instanceof PrivateKeyInfo pki) {
                        privateKeyInfo = pki;
                        break;
                    }
                }
                if (privateKeyInfo == null) {
                    throw new IllegalArgumentException("No EC private key found in PEM");
                }
                StringWriter sw = new StringWriter();
                try (JcaPEMWriter pemWriter = new JcaPEMWriter(sw)) {
                    pemWriter.writeObject(privateKeyInfo);
                }
                return sw.toString();
            }
        } catch (Exception e) {
            throw new IllegalStateException("EC Key conversion failed", e);
        }
    }
}
