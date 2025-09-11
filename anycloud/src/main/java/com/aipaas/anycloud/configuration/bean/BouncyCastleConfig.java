package com.aipaas.anycloud.configuration.bean;

import jakarta.annotation.PostConstruct;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.springframework.context.annotation.Configuration;
import java.security.Security;
import java.util.Arrays;

@Configuration
public class BouncyCastleConfig {

	@PostConstruct
	public void registerProvider() {
		// 중복 등록 방지
		if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
			Security.addProvider(new BouncyCastleProvider());
			System.out.println("✅ BouncyCastle Provider 등록 완료");
		}

		// 디버깅용: 로드된 Provider 목록 확인
		Arrays.stream(Security.getProviders())
			.forEach(p -> System.out.println("Security Provider loaded: " + p.getName()));
	}
}
