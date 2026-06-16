package com.aipaas.anycloud.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aipaas.anycloud.domain.agent.IdempotencyRecordEntity;
import com.aipaas.anycloud.domain.agent.IdempotencyRecordRepository;
import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * IdempotencyFilter 의 핵심 시나리오 회귀 방지.
 * 모든 케이스: 필터 내부 흐름만 검증 (외부 controller 호출은 mock chain 으로 표현).
 */
class IdempotencyFilterTest extends AbstractUnitTest {

    @Mock
    IdempotencyRecordRepository repository;

    private static final String KEY = "test-key-12345";

    /** chain 에 들어왔을 때 200 + body 를 쓰는 "controller" 모킹. */
    private static FilterChain controllerReturning(int status, String body) {
        return (req, res) -> {
            HttpServletResponse r = (HttpServletResponse) res;
            r.setStatus(status);
            r.setContentType("application/json");
            r.getWriter().write(body);
            r.getWriter().flush();
        };
    }

    private MockHttpServletRequest postWithKey(String body) {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/v1/clusters");
        req.addHeader(IdempotencyFilter.IDEMPOTENCY_HEADER, KEY);
        req.setContent(body.getBytes(StandardCharsets.UTF_8));
        req.setContentType("application/json");
        return req;
    }

    @Test
    void missingHeader_doesNotEngage_filter() throws ServletException, IOException {
        IdempotencyFilter filter = new IdempotencyFilter(repository);
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/v1/clusters");
        // no Idempotency-Key 헤더
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(req, res, controllerReturning(202, "{\"data\":\"x\"}"));

        assertThat(res.getStatus()).isEqualTo(202);
        assertThat(res.getContentAsString()).isEqualTo("{\"data\":\"x\"}");
        verify(repository, never()).findById(any());
        verify(repository, never()).save(any());
    }

    @Test
    void getMethod_doesNotEngage_filter() throws ServletException, IOException {
        IdempotencyFilter filter = new IdempotencyFilter(repository);
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/v1/clusters");
        req.addHeader(IdempotencyFilter.IDEMPOTENCY_HEADER, KEY);
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(req, res, controllerReturning(200, "ok"));

        verify(repository, never()).findById(any());
    }

    @Test
    void firstRequest_cachesSuccessResponse() throws ServletException, IOException {
        IdempotencyFilter filter = new IdempotencyFilter(repository);
        when(repository.findById(KEY)).thenReturn(Optional.empty());

        MockHttpServletRequest req = postWithKey("{\"clusterName\":\"demo\"}");
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(req, res, controllerReturning(202, "{\"opId\":\"op-1\"}"));

        assertThat(res.getStatus()).isEqualTo(202);
        // 2xx 응답이므로 캐싱.
        verify(repository, times(1)).save(any(IdempotencyRecordEntity.class));
    }

    @Test
    void firstRequest_doesNotCacheFailureResponse() throws ServletException, IOException {
        IdempotencyFilter filter = new IdempotencyFilter(repository);
        when(repository.findById(KEY)).thenReturn(Optional.empty());

        MockHttpServletRequest req = postWithKey("{\"clusterName\":\"demo\"}");
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(req, res, controllerReturning(500, "{\"error\":\"boom\"}"));

        assertThat(res.getStatus()).isEqualTo(500);
        // 5xx 는 캐싱하지 않음 — 재시도 마다 같은 5xx 받지 않도록.
        verify(repository, never()).save(any(IdempotencyRecordEntity.class));
    }

    @Test
    void replay_sameKeyAndBody_returnsCachedResponseAndSkipsController() throws ServletException, IOException {
        IdempotencyFilter filter = new IdempotencyFilter(repository);
        String body = "{\"clusterName\":\"demo\"}";
        // 같은 fingerprint 가 들어와야 하므로 첫 번째 요청을 캡처해 fingerprint 를 계산하고 cached row 를 stub.
        // 간단히 같은 method+uri+body 의 SHA-256 의 앞 32자.
        String fp = fingerprintFor("POST", "/v1/clusters", body);
        IdempotencyRecordEntity cached = IdempotencyRecordEntity.builder()
                .idempotencyKey(KEY)
                .requestFingerprint(fp)
                .statusCode(202)
                .responseBody("{\"opId\":\"op-cached\"}")
                .expiresAt(LocalDateTime.now().plusHours(23))
                .build();
        when(repository.findById(KEY)).thenReturn(Optional.of(cached));

        MockHttpServletRequest req = postWithKey(body);
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain shouldNotInvoke = (rq, rs) -> {
            throw new AssertionError("controller should NOT be invoked on cached replay");
        };
        filter.doFilter(req, res, shouldNotInvoke);

        assertThat(res.getStatus()).isEqualTo(202);
        assertThat(res.getContentAsString()).isEqualTo("{\"opId\":\"op-cached\"}");
        // UX #5: replay 시 X-Idempotency-Replay: true — 클라이언트가 cached 응답임을 인지.
        assertThat(res.getHeader(IdempotencyFilter.IDEMPOTENCY_REPLAY_HEADER)).isEqualTo("true");
        // body 가 보존되었으므로 truncated 헤더는 없어야 함.
        assertThat(res.getHeader(IdempotencyFilter.IDEMPOTENCY_BODY_TRUNCATED_HEADER))
                .isNull();
    }

    @Test
    void replay_bodyTruncatedAtCacheTime_emitsTruncatedHintHeader() throws ServletException, IOException {
        // UX #5: 1MB 초과로 body=null 인 채 캐시된 record 의 replay 시나리오.
        IdempotencyFilter filter = new IdempotencyFilter(repository);
        String body = "{\"clusterName\":\"demo\"}";
        IdempotencyRecordEntity cached = IdempotencyRecordEntity.builder()
                .idempotencyKey(KEY)
                .requestFingerprint(fingerprintFor("POST", "/v1/clusters", body))
                .statusCode(202)
                .responseBody(null) // 1MB 초과로 잘렸음을 시뮬레이션.
                .expiresAt(LocalDateTime.now().plusHours(23))
                .build();
        when(repository.findById(KEY)).thenReturn(Optional.of(cached));

        MockHttpServletRequest req = postWithKey(body);
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(req, res, (rq, rs) -> {
            throw new AssertionError("controller should NOT be invoked on cached replay");
        });

        assertThat(res.getStatus()).isEqualTo(202);
        assertThat(res.getContentAsString()).isEmpty();
        // 두 헤더 모두 emit — 빈 body 가 의도된 결과임을 클라이언트가 알 수 있어야 함.
        assertThat(res.getHeader(IdempotencyFilter.IDEMPOTENCY_REPLAY_HEADER)).isEqualTo("true");
        assertThat(res.getHeader(IdempotencyFilter.IDEMPOTENCY_BODY_TRUNCATED_HEADER))
                .isEqualTo("true");
    }

    @Test
    void firstRequest_doesNotEmitReplayHeader() throws ServletException, IOException {
        // UX #5: 첫 호출은 replay 가 아니므로 헤더 없음.
        IdempotencyFilter filter = new IdempotencyFilter(repository);
        when(repository.findById(KEY)).thenReturn(Optional.empty());

        MockHttpServletRequest req = postWithKey("{}");
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(req, res, controllerReturning(202, "{\"opId\":\"op-1\"}"));

        assertThat(res.getHeader(IdempotencyFilter.IDEMPOTENCY_REPLAY_HEADER)).isNull();
        assertThat(res.getHeader(IdempotencyFilter.IDEMPOTENCY_BODY_TRUNCATED_HEADER))
                .isNull();
    }

    @Test
    void conflict_sameKeyDifferentBody_returns409AndSkipsController() throws ServletException, IOException {
        IdempotencyFilter filter = new IdempotencyFilter(repository);
        IdempotencyRecordEntity cached = IdempotencyRecordEntity.builder()
                .idempotencyKey(KEY)
                .requestFingerprint(fingerprintFor("POST", "/v1/clusters", "{\"clusterName\":\"original\"}"))
                .statusCode(202)
                .responseBody("{\"opId\":\"op-orig\"}")
                .expiresAt(LocalDateTime.now().plusHours(23))
                .build();
        when(repository.findById(KEY)).thenReturn(Optional.of(cached));

        MockHttpServletRequest req = postWithKey("{\"clusterName\":\"DIFFERENT\"}");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain shouldNotInvoke = (rq, rs) -> {
            throw new AssertionError("controller should NOT be invoked on conflict");
        };
        filter.doFilter(req, res, shouldNotInvoke);

        assertThat(res.getStatus()).isEqualTo(409);
        assertThat(res.getContentAsString()).contains("Idempotency-Key conflict");
    }

    @Test
    void expiredRecord_isPurgedAndProceedsAsFresh() throws ServletException, IOException {
        IdempotencyFilter filter = new IdempotencyFilter(repository);
        IdempotencyRecordEntity expired = IdempotencyRecordEntity.builder()
                .idempotencyKey(KEY)
                .requestFingerprint("doesntmatter")
                .statusCode(202)
                .responseBody("old")
                .expiresAt(LocalDateTime.now().minusHours(1)) // 만료
                .build();
        when(repository.findById(KEY)).thenReturn(Optional.of(expired));

        MockHttpServletRequest req = postWithKey("{}");
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(req, res, controllerReturning(202, "{\"opId\":\"op-new\"}"));

        // 만료된 row 삭제 후 진행
        verify(repository).deleteById(eq(KEY));
        assertThat(res.getStatus()).isEqualTo(202);
        verify(repository, times(1)).save(any(IdempotencyRecordEntity.class));
    }

    // IdempotencyFilter 의 fingerprint 와 동일 알고리즘 (METHOD\0URI\0body, SHA-256 앞 32 hex chars).
    private static String fingerprintFor(String method, String uri, String body) {
        try {
            var md = java.security.MessageDigest.getInstance("SHA-256");
            md.update(method.getBytes(StandardCharsets.UTF_8));
            md.update((byte) 0);
            md.update(uri.getBytes(StandardCharsets.UTF_8));
            md.update((byte) 0);
            md.update(body.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(md.digest()).substring(0, 32);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
