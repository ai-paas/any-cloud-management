package com.aipaas.anycloud.domain.agent.internal;

import com.aipaas.anycloud.domain.agent.AgentProperties;
import io.aipaas.cluster.agent.identity.AgentJwtProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Cluster Agent configuration root. */
@Configuration
@EnableConfigurationProperties({AgentJwtProperties.class, AgentProperties.class})
public class AgentConfiguration {}
