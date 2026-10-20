package com.aipaas.anycloud.domain.vmoptions;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipaas.anycloud.domain.vmoptions.internal.VmOptionsServiceImpl;
import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.cache.annotation.Cacheable;

/**
 * 빈 결과가 캐시에 저장되면 TTL 동안 해당 상태 고정.
 *
 * <p>잘못된 자격증명 1회로 목록이 비고, 자격증명 수정 후에도 화면이 계속 빈 상태 유지. 원인이
 * 캐시임이 드러나지 않아 코드 결함으로 오진. 해당 사례 발생.
 *
 * <p>애노테이션 기준 검증. 캐시 매니저 기동은 테스트 비용이 크고, 검증 대상은 빈 결과 미저장
 * 선언 하나.
 */
class EmptyResultsAreNotCachedTest extends AbstractUnitTest {

    @Test
    void everyCachedLookupSkipsEmptyResults() {
        for (Method method : VmOptionsServiceImpl.class.getDeclaredMethods()) {
            Cacheable cacheable = method.getAnnotation(Cacheable.class);
            if (cacheable == null) {
                continue;
            }
            assertThat(cacheable.unless())
                    .as("%s 가 빈 결과를 캐시한다 — 자격증명을 고쳐도 TTL 동안 빈 목록이 남는다", method.getName())
                    .contains("isEmpty()");
        }
    }

    @Test
    void theLookupsThatTalkToTheCloudAreCached() {
        // 캐시 제거 시 화면 진입마다 CSP API 호출로 요청 제한 도달.
        assertThat(cachedMethodNames()).contains("getRegions", "getSpecs", "getImages");
    }

    private static java.util.List<String> cachedMethodNames() {
        return java.util.Arrays.stream(VmOptionsServiceImpl.class.getDeclaredMethods())
                .filter(m -> m.getAnnotation(Cacheable.class) != null)
                .map(Method::getName)
                .toList();
    }
}
