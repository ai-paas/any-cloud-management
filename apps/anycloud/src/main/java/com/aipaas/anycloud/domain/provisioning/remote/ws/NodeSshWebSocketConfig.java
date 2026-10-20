package com.aipaas.anycloud.domain.provisioning.remote.ws;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * 노드 SSH 터미널 경로 등록.
 *
 * <p>노드 root 셸의 웹 접근 경로. 불필요한 배포에서는 비활성화 가능해야 함.
 */
@Configuration
@EnableWebSocket
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "anycloud.node-ssh", name = "enabled", matchIfMissing = true)
public class NodeSshWebSocketConfig implements WebSocketConfigurer {

    private final NodeSshWebSocketHandler handler;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/v1/vms/*/nodes/*/ssh").setAllowedOriginPatterns("*");
    }
}
