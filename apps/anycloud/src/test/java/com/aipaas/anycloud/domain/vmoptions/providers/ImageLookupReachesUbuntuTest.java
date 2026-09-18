package com.aipaas.anycloud.domain.vmoptions.providers;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

/**
 * 이미지 목록을 CSP 가 준 첫 페이지 안에서만 거르면 Ubuntu 를 못 찾는다.
 *
 * <p>Alibaba 는 PageSize 를 주지 않으면 10건만 주고 그 안은 aliyun_* 로 채워진다. OCI 는 목록이
 * Windows 로 먼저 채워져 Ubuntu 가 뒤로 밀리고, 이름 대신 operatingSystem 으로 배포판을 준다.
 * 둘 다 결과가 비어 "이 리전엔 Ubuntu 가 없다" 로 보였다.
 */
class ImageLookupReachesUbuntuTest extends AbstractUnitTest {

    private static String sourceOf(Class<?> type) throws Exception {
        java.nio.file.Path path =
                java.nio.file.Path.of("src/main/java", type.getName().replace('.', '/') + ".java");
        return java.nio.file.Files.readString(path);
    }

    @Test
    void alibabaAsksForMoreThanTheDefaultPage() throws Exception {
        assertThat(sourceOf(AlibabaVmOptionsProvider.class))
                .as("PageSize 를 주지 않으면 DescribeImages 가 10건만 준다")
                .contains("\"PageSize\"");
    }

    @Test
    void ociNarrowsByOperatingSystemInsteadOfScanningNames() throws Exception {
        assertThat(sourceOf(OciVmOptionsProvider.class))
                .as("이름만으로 거르면 Windows 가 채운 첫 페이지에서 끝난다")
                .contains("operatingSystem=")
                .contains("Canonical Ubuntu");
    }

    @Test
    void ociSendsAUriSoTheFilterIsNotDoubleEncoded() throws Exception {
        // String 오버로드는 %20 을 %2520 으로 만들어 필터가 아무것도 걸러내지 않는다.
        assertThat(sourceOf(OciVmOptionsProvider.class)).contains("URI.create(url)");
    }

    @Test
    void gcpAsksForTheNewestImagesFirst() throws Exception {
        // 기본 정렬은 이름순이라 폐기 이미지가 많은 ubuntu-os-cloud 는 첫 페이지가 통째로 폐기본이다.
        assertThat(sourceOf(GcpVmOptionsProvider.class)).contains("orderBy=creationTimestamp");
    }

    @Test
    void providersThatEncodeQueryValuesSendAUri() throws Exception {
        /*
         * String 오버로드는 URI 템플릿으로 취급해 %20 을 %2520 으로 만든다. GCP 는 400 으로
         * 거절했고 OCI 는 필터가 아무것도 걸러내지 않았다. 세 provider 에서 같은 실수가 났다.
         */
        for (Class<?> type :
                new Class<?>[] {GcpVmOptionsProvider.class, OciVmOptionsProvider.class, AlibabaVmOptionsProvider.class
                }) {
            assertThat(sourceOf(type)).as("%s", type.getSimpleName()).contains("URI.create(url)");
        }
    }

    @Test
    void everyProviderTakesTheKeywordItIsGiven() {
        // 서명이 바뀌면 이미지 선택지 채우기가 조용히 빈 목록으로 떨어진다.
        for (Class<?> type :
                new Class<?>[] {AlibabaVmOptionsProvider.class, OciVmOptionsProvider.class, IbmVmOptionsProvider.class
                }) {
            assertThat(java.util.Arrays.stream(type.getDeclaredMethods())
                            .filter(m -> "listImages".equals(m.getName()))
                            .map(Method::getParameterCount))
                    .as("%s", type.getSimpleName())
                    .contains(5);
        }
    }
}
