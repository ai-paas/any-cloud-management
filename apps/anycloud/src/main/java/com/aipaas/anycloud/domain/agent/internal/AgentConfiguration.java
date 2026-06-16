package com.aipaas.anycloud.domain.agent.internal;

import com.aipaas.anycloud.domain.agent.AgentProperties;
import io.aipaas.cluster.agent.identity.AgentJwtProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Cluster Agent  configuration root.
 *
 * <p>JWT signing 키, identity token TTL, gRPC port 등 agent 관련 모든 설정을
 * 한 곳에서 노출. application.yaml 의 {@code agent.*} 아래.
 */
@Configuration
@EnableConfigurationProperties({AgentJwtProperties.class, AgentProperties.class})
public class AgentConfiguration {}
