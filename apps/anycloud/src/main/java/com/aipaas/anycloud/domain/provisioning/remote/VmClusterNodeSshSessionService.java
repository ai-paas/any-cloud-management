package com.aipaas.anycloud.domain.provisioning.remote;

import org.springframework.web.socket.WebSocketSession;

/** 브라우저 터미널 하나에 노드 ssh 프로세스 하나를 붙인다. */
public interface VmClusterNodeSshSessionService {

    void open(WebSocketSession session, String vmName, String host);

    /** 사용자가 친 글자를 원격 stdin 으로 보낸다. */
    void write(WebSocketSession session, byte[] data);

    /** 창 크기 변경 같은 제어 프레임 (JSON). */
    void control(WebSocketSession session, String payload);

    void close(WebSocketSession session);
}
