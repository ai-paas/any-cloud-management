package com.aipaas.anycloud.configuration.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import io.aipaas.cluster.agent.identity.ImpersonationIdentity;
import io.aipaas.cluster.agent.identity.ThreadLocalImpersonationContext;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Gateway header → ThreadLocal identity 변환 회귀 방지.
 *
 * <p>edge case coverage:
 * <ul>
 *   <li>missing user — no-op, ThreadLocal 비어 있음</li>
 *   <li>user only — groups/extras 빈 채 set</li>
 *   <li>CSV groups single header — 분해</li>
 *   <li>multi-value groups header — 합치기</li>
 *   <li>X-Forwarded-Extra-&lt;key&gt; — case-insensitive prefix, multi-value, CSV</li>
 *   <li>afterCompletion — ThreadLocal cleanup (leak 방지)</li>
 *   <li>preHandle 두 번 호출 (같은 thread 재진입) — leak 없이 덮어쓰기</li>
 * </ul>
 */
class ImpersonationInterceptorTest extends AbstractUnitTest {

    private final ImpersonationInterceptor interceptor = new ImpersonationInterceptor();
    private final MockHttpServletResponse response = new MockHttpServletResponse();

    @AfterEach
    void cleanup() {
        // 어떤 테스트가 깜빡 leak 시켜도 다음 테스트에 영향 X.
        ThreadLocalImpersonationContext.clear();
    }

    @Test
    void preHandle_missingUserHeader_noopThreadLocalEmpty() {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/v1/anything");

        interceptor.preHandle(req, response, new Object());

        assertThat(currentIdentity()).isEmpty();
    }

    @Test
    void preHandle_blankUserHeader_noopThreadLocalEmpty() {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/v1/anything");
        req.addHeader(ImpersonationInterceptor.USER_HEADER, "   ");

        interceptor.preHandle(req, response, new Object());

        assertThat(currentIdentity()).isEmpty();
    }

    @Test
    void preHandle_userOnly_groupsAndExtrasEmpty() {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/v1/anything");
        req.addHeader(ImpersonationInterceptor.USER_HEADER, "alice@example.com");

        interceptor.preHandle(req, response, new Object());

        ImpersonationIdentity id = currentIdentity().orElseThrow();
        assertThat(id.user()).isEqualTo("alice@example.com");
        assertThat(id.groups()).isEmpty();
        assertThat(id.extras()).isEmpty();
    }

    @Test
    void preHandle_csvGroupsSingleHeader_split() {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/v1/anything");
        req.addHeader(ImpersonationInterceptor.USER_HEADER, "alice");
        req.addHeader(ImpersonationInterceptor.GROUPS_HEADER, "dev-team, ops-team ,viewer");

        interceptor.preHandle(req, response, new Object());

        ImpersonationIdentity id = currentIdentity().orElseThrow();
        assertThat(id.groups()).containsExactly("dev-team", "ops-team", "viewer");
    }

    @Test
    void preHandle_multiValueGroupsHeader_merged() {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/v1/anything");
        req.addHeader(ImpersonationInterceptor.USER_HEADER, "alice");
        // MockHttpServletRequest 의 addHeader 는 같은 이름 두 번 호출 시 multi-value 로 누적.
        req.addHeader(ImpersonationInterceptor.GROUPS_HEADER, "dev-team");
        req.addHeader(ImpersonationInterceptor.GROUPS_HEADER, "ops-team,viewer");

        interceptor.preHandle(req, response, new Object());

        ImpersonationIdentity id = currentIdentity().orElseThrow();
        assertThat(id.groups()).containsExactly("dev-team", "ops-team", "viewer");
    }

    @Test
    void preHandle_extraHeaders_caseInsensitiveAndMultiValue() {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/v1/anything");
        req.addHeader(ImpersonationInterceptor.USER_HEADER, "alice");
        // 대소문자 혼합 — HTTP header name 은 case-insensitive 이지만 servlet container 에 따라 보존.
        req.addHeader("X-Forwarded-Extra-Scopes", "read,write");
        req.addHeader("x-forwarded-extra-tenant", "team-alpha");
        // prefix-only (key 비어 있음) → 무시.
        req.addHeader("X-Forwarded-Extra-", "ignored");

        interceptor.preHandle(req, response, new Object());

        ImpersonationIdentity id = currentIdentity().orElseThrow();
        assertThat(id.extras()).containsOnlyKeys("scopes", "tenant");
        assertThat(id.extras().get("scopes")).containsExactly("read", "write");
        assertThat(id.extras().get("tenant")).containsExactly("team-alpha");
    }

    @Test
    void afterCompletion_clearsThreadLocal_preventsLeak() {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/v1/anything");
        req.addHeader(ImpersonationInterceptor.USER_HEADER, "alice");

        interceptor.preHandle(req, response, new Object());
        assertThat(currentIdentity()).isPresent();

        interceptor.afterCompletion(req, response, new Object(), null);

        assertThat(currentIdentity()).isEmpty();
    }

    @Test
    void afterCompletion_evenWithException_stillClears() {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/v1/anything");
        req.addHeader(ImpersonationInterceptor.USER_HEADER, "alice");

        interceptor.preHandle(req, response, new Object());

        // controller 가 throw 한 상황을 모사 — interceptor 는 항상 clear 해야 leak 없음.
        interceptor.afterCompletion(req, response, new Object(), new RuntimeException("boom"));

        assertThat(currentIdentity()).isEmpty();
    }

    @Test
    void preHandle_repeatedOnSameThread_overwritesNotLeaks() {
        // thread pool 이 같은 thread 를 reuse 하는 케이스. preHandle 두 번 연속 호출 시 두 번째가 첫 번째
        // 를 덮어써야 함 (어쨌든 afterCompletion 도 매 request 마다 호출되므로 leak 은 없음).
        MockHttpServletRequest req1 = new MockHttpServletRequest("GET", "/v1/r1");
        req1.addHeader(ImpersonationInterceptor.USER_HEADER, "alice");
        interceptor.preHandle(req1, response, new Object());
        assertThat(currentIdentity().orElseThrow().user()).isEqualTo("alice");

        MockHttpServletRequest req2 = new MockHttpServletRequest("GET", "/v1/r2");
        req2.addHeader(ImpersonationInterceptor.USER_HEADER, "bob");
        interceptor.preHandle(req2, response, new Object());
        assertThat(currentIdentity().orElseThrow().user()).isEqualTo("bob");

        interceptor.afterCompletion(req2, response, new Object(), null);
        assertThat(currentIdentity()).isEmpty();
    }

    private static Optional<ImpersonationIdentity> currentIdentity() {
        return new ThreadLocalImpersonationContext().current();
    }
}
