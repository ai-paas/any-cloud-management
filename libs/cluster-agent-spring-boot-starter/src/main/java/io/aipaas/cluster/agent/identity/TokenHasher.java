package io.aipaas.cluster.agent.identity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 토큰 평문 → SHA-256 hex digest. DB 에는 평문 token 대신 본 hash 만 저장.
 *
 * <p>JDK 의 {@link MessageDigest} 는 thread-safe 가 아니지만, 본 helper 는 매 호출 시 new instance 를
 * 생성하므로 사용 측에서 동시성 우려 없음.
 */
public final class TokenHasher {

	private TokenHasher() {}

	/** SHA-256 hex digest. lowercase 64 chars. */
	public static String sha256Hex(String value) {
		try {
			MessageDigest md = MessageDigest.getInstance("SHA-256");
			byte[] digest = md.digest(value.getBytes(StandardCharsets.UTF_8));
			StringBuilder sb = new StringBuilder(digest.length * 2);
			for (byte b : digest) {
				sb.append(String.format("%02x", b));
			}
			return sb.toString();
		} catch (NoSuchAlgorithmException e) {
			// SHA-256 은 JDK 표준 — 절대 발생 안 함.
			throw new IllegalStateException("SHA-256 unavailable", e);
		}
	}
}
