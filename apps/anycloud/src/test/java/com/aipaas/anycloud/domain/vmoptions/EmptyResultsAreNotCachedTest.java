package com.aipaas.anycloud.domain.vmoptions;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipaas.anycloud.domain.vmoptions.internal.VmOptionsServiceImpl;
import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.cache.annotation.Cacheable;

/**
 * 빈 결과가 캐시에 남으면 그 상태가 TTL 동안 고정된다.
 *
 * <p>잘못된 자격증명 한 번이 목록을 비우고, 자격증명을 고쳐도 화면은 계속 비어 있다. 원인이
 * 캐시라는 사실이 드러나지 않아 코드 결함으로 오진하게 된다 — 실제로 그랬다.
 *
 * <p>애노테이션을 읽어 확인한다. 캐시 매니저를 띄우면 테스트가 무거워지는데, 지켜야 하는 것은
 * "빈 결과를 담지 않는다"는 선언 하나다.
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
        // 캐시가 빠지면 화면을 열 때마다 CSP API 를 쳐서 요청 제한에 걸린다.
        assertThat(cachedMethodNames()).contains("getRegions", "getSpecs", "getImages");
    }

    private static java.util.List<String> cachedMethodNames() {
        return java.util.Arrays.stream(VmOptionsServiceImpl.class.getDeclaredMethods())
                .filter(m -> m.getAnnotation(Cacheable.class) != null)
                .map(Method::getName)
                .toList();
    }
}
