package com.aipaas.anycloud.domain.events.web;

import com.aipaas.anycloud.domain.events.ResourceEventBroker;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 화면 하나당 스트림 하나. 자원별로 스트림을 늘리면 화면 수만큼 연결이 생긴다.
 *
 * <p>여기로는 "무엇이 바뀌었다"만 흐른다. 값은 REST 가 계속 책임진다.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/v1")
@Tag(name = "Resource Events (v1)", description = "자원 변경 신호 단일 스트림 (SSE)")
public class ResourceEventsController {

    private static final Duration SSE_TIMEOUT = Duration.ofHours(1);

    private final ResourceEventBroker broker;

    @GetMapping(path = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "자원 변경 신호 SSE", description = "값이 아니라 무엇이 바뀌었는지만 흘린다. 화면은 해당 조회를 다시 부른다.")
    public SseEmitter events() {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT.toMillis());
        broker.register(emitter);
        emitter.onCompletion(() -> broker.unregister(emitter));
        emitter.onTimeout(() -> broker.unregister(emitter));
        emitter.onError(e -> broker.unregister(emitter));
        return emitter;
    }
}
