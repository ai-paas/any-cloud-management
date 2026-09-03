package com.aipaas.anycloud.configuration.infrastructure;

import jakarta.annotation.PostConstruct;
import java.security.Security;
import java.util.Arrays;
import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
public class BouncyCastleConfig {

    @PostConstruct
    public void registerProvider() {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
            log.info("BouncyCastle Provider registered");
        }

        if (log.isDebugEnabled()) {
            Arrays.stream(Security.getProviders()).forEach(p -> log.debug("Security Provider loaded: {}", p.getName()));
        }
    }
}
