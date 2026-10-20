package com.aipaas.anycloud.domain.provisioning.remote.ws;

import com.aipaas.anycloud.domain.provisioning.remote.VmClusterNodeSshSessionService;
import java.nio.ByteBuffer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

/**
 * 브라우저 터미널과 노드의 ssh 프로세스 연결.
 *
 * <p>파드 exec 는 에이전트 경유, 노드는 클러스터 외부이므로 백엔드가 ssh 직접 실행.
 * 클러스터 장애로 파드 셸 사용이 불가할 때의 경로이므로 에이전트 의존 금지.
 *
 * <p>경로: {@code /v1/vms/{vmName}/nodes/{host}/ssh}
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NodeSshWebSocketHandler extends AbstractWebSocketHandler {

    private final VmClusterNodeSshSessionService sessionService;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        NodeSshTarget target = NodeSshTarget.from(session.getUri());
        if (target == null) {
            closeQuietly(session, CloseStatus.BAD_DATA.withReason("경로에서 클러스터와 노드를 읽지 못했습니다"));
            return;
        }
        try {
            sessionService.open(session, target.vmName(), target.host());
        } catch (Exception e) {
            log.info("노드 SSH 열기 실패 cluster={} host={}: {}", target.vmName(), target.host(), e.toString());
            sendText(session, "\r\n[연결 실패] " + e.getMessage() + "\r\n");
            closeQuietly(session, CloseStatus.SERVER_ERROR);
        }
    }

    /** 사용자 입력. 터미널 입력이 텍스트가 아닐 수 있어 바이너리로 수신. */
    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        ByteBuffer payload = message.getPayload();
        byte[] bytes = new byte[payload.remaining()];
        payload.get(bytes);
        sessionService.write(session, bytes);
    }

    /** 창 크기 변경 같은 제어 프레임. */
    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        sessionService.control(session, message.getPayload());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessionService.close(session);
    }

    private void sendText(WebSocketSession session, String text) {
        try {
            if (session.isOpen()) {
                session.sendMessage(new TextMessage(text));
            }
        } catch (Exception ignored) {
            // 연결 종료 상태면 통보 대상 부재.
        }
    }

    private void closeQuietly(WebSocketSession session, CloseStatus status) {
        try {
            session.close(status);
        } catch (Exception ignored) {
            // 이미 닫혔다.
        }
    }
}
