package com.aipaas.anycloud.configuration;

import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 시간 의존 로직(백오프 등)의 테스트 고정을 위해 Clock 주입 가능 구성. */
@Configuration
public class ClockConfig {

    @Bean
    @ConditionalOnMissingBean
    public Clock systemClock() {
        return Clock.systemDefaultZone();
    }
}
