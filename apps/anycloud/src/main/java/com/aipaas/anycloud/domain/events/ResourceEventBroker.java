package com.aipaas.anycloud.domain.events;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 열려 있는 모든 스트림에 변경 신호를 뿌린다. */
@Slf4j
@Component
public class ResourceEventBroker {

    private final Set<SseEmitter> listeners = ConcurrentHashMap.newKeySet();

    public void register(SseEmitter emitter) {
        listeners.add(emitter);
    }

    public void unregister(SseEmitter emitter) {
        listeners.remove(emitter);
    }

    public int listenerCount() {
        return listeners.size();
    }

    @EventListener
    public void onResourceChanged(ResourceChangedEvent event) {
        broadcast(SseEmitter.event()
                .name("changed")
                .data(Map.of("type", event.type(), "name", event.name() == null ? "" : event.name())));
    }

    /**
     * 조용한 스트림은 프록시가 끊는다 (nginx proxy_read_timeout 900s).
     * comment 프레임이라 클라이언트의 message 핸들러를 건드리지 않는다.
     */
    @Scheduled(fixedDelayString = "${anycloud.events.heartbeat-ms:20000}")
    public void heartbeat() {
        broadcast(SseEmitter.event().comment("ping"));
    }

    private void broadcast(SseEmitter.SseEventBuilder payload) {
        for (SseEmitter emitter : listeners) {
            try {
                emitter.send(payload);
            } catch (Exception e) {
                // 끊긴 연결을 남겨두면 이벤트마다 예외가 쌓이고 메모리도 샌다.
                listeners.remove(emitter);
            }
        }
    }
}
