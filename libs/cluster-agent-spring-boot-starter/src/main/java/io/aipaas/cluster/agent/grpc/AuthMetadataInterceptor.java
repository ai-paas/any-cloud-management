package io.aipaas.cluster.agent.grpc;

import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Grpc;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.server.interceptor.GrpcGlobalServerInterceptor;

/**
 * gRPC metadata 의 Authorization header + (mTLS Phase mtls.2+) client cert subject CN 을
 * {@link io.grpc.Context} 로 옮긴다. handler 가 {@link #AUTHORIZATION_CONTEXT} /
 * {@link #CLIENT_CERT_CLUSTER_CONTEXT} 으로 ThreadLocal-safe 하게 접근.
 *
 * <p>대안: handler 에서 직접 {@link ServerCall#getMethodDescriptor()} 등으로 metadata 접근. 그러나
 * 별도 ServerInterceptor + Context 가 표준 패턴.
 *
 * <h3>mTLS client cert identity 추출</h3>
 *
 * <p>gRPC connection 의 {@link SSLSession} 에서 peer X.509 cert chain 의 leaf 의 subject CN
 * (예: {@code cluster:my-prod}) 을 추출 → cluster name (prefix 제거) 으로 변환해
 * {@link #CLIENT_CERT_CLUSTER_CONTEXT} 에 set. Bearer 토큰 인증이 fallback 으로 남아있어 두 식별
 * 자 모두 endpoint 에서 사용 가능.
 *
 * <p>Phase mtls.2 (opt-in): mTLS connection 이면 cert subject 도 set, 아니면 bearer 만. Phase
 * mtls.4 에서는 cert subject 만으로 인증.
 */
@Slf4j
@GrpcGlobalServerInterceptor
public class AuthMetadataInterceptor implements ServerInterceptor {

	public static final Metadata.Key<String> AUTHORIZATION_KEY =
			Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);

	/** Bearer 토큰 (full "Bearer <token>" 형태). */
	public static final Context.Key<String> AUTHORIZATION_CONTEXT = Context.key("authorization");

	/**
	 * mTLS client cert 의 subject CN 에서 추출한 cluster name. mTLS connection 이 아니거나 CN 이
	 * {@code cluster:} prefix 형식이 아니면 null. Phase mtls.2 이후 endpoint 가 bearer fallback
	 * 과 비교해 cluster_agent row lookup 의 추가 신호로 사용.
	 */
	public static final Context.Key<String> CLIENT_CERT_CLUSTER_CONTEXT = Context.key("clientCertCluster");

	/**
	 * mTLS client cert 의 X.509 serial (hex, lowercase). Phase mtls.4 의 cert-only auth path 가
	 * cluster_name + cert_serial 조합으로 cluster_agent row 의 agent_instance 까지 유일 식별.
	 * mTLS connection 이 아니면 null.
	 */
	public static final Context.Key<String> CLIENT_CERT_SERIAL_CONTEXT = Context.key("clientCertSerial");

	private static final String CLUSTER_CN_PREFIX = "cluster:";

	@Override
	public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
			ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
		String auth = headers.get(AUTHORIZATION_KEY);
		CertPeer peer = extractCertPeer(call);
		Context ctx = Context.current()
				.withValue(AUTHORIZATION_CONTEXT, auth)
				.withValue(CLIENT_CERT_CLUSTER_CONTEXT, peer == null ? null : peer.cluster())
				.withValue(CLIENT_CERT_SERIAL_CONTEXT, peer == null ? null : peer.serial());
		return Contexts.interceptCall(ctx, call, headers, next);
	}

	/** mTLS leaf cert 에서 추출한 cluster name + serial. mTLS 아니면 null 반환. */
	private record CertPeer(String cluster, String serial) {}

	/**
	 * mTLS connection 의 peer cert chain 에서 leaf cert 의 CN + serial 추출.
	 * 형식 {@code cluster:<name>} 이 아니거나 mTLS 가 아니면 null.
	 */
	private static CertPeer extractCertPeer(ServerCall<?, ?> call) {
		SSLSession ssl = call.getAttributes().get(Grpc.TRANSPORT_ATTR_SSL_SESSION);
		if (ssl == null) {
			return null;
		}
		try {
			Certificate[] peers = ssl.getPeerCertificates();
			if (peers == null || peers.length == 0) {
				return null;
			}
			if (!(peers[0] instanceof X509Certificate leaf)) {
				return null;
			}
			String dn = leaf.getSubjectX500Principal().getName();
			String cn = extractCn(dn);
			if (cn == null || !cn.startsWith(CLUSTER_CN_PREFIX)) {
				log.debug("mTLS cert CN does not match expected prefix: {}", cn);
				return null;
			}
			String cluster = cn.substring(CLUSTER_CN_PREFIX.length());
			// Serial — BackendCa 가 hex 로 저장하므로 hex (lowercase) 로 정규화.
			String serial = leaf.getSerialNumber().toString(16).toLowerCase();
			return new CertPeer(cluster, serial);
		} catch (SSLPeerUnverifiedException e) {
			// mTLS 미설정 또는 client 가 cert 안 보냄. 정상 — bearer 인증으로 fallback.
			return null;
		}
	}

	/** {@code CN=foo, OU=bar, O=baz} 형식에서 CN 값을 추출. */
	private static String extractCn(String dn) {
		for (String part : dn.split(",\\s*")) {
			if (part.startsWith("CN=")) {
				return part.substring(3);
			}
		}
		return null;
	}
}
