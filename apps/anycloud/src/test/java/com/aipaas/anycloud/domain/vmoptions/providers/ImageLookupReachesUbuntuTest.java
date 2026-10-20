package com.aipaas.anycloud.domain.vmoptions.providers;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

/**
 * 이미지 목록을 CSP 응답 첫 페이지 내에서만 필터링하면 Ubuntu 미검출.
 *
 * <p>Alibaba 는 PageSize 미지정 시 10건만 반환하고 그 안이 aliyun_* 로 채워짐. OCI 는 목록
 * 선두가 Windows 로 채워져 Ubuntu 가 후순위로 밀리고 이름 대신 operatingSystem 으로 배포판 제공.
 * 양쪽 모두 결과가 비어 해당 리전의 Ubuntu 부재로 오인.
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
        // String 오버로드는 %20 을 %2520 으로 변환해 필터가 동작하지 않음.
        assertThat(sourceOf(OciVmOptionsProvider.class)).contains("URI.create(url)");
    }

    @Test
    void gcpAsksForTheNewestImagesFirst() throws Exception {
        // 기본 정렬이 이름순이라 폐기 이미지가 많은 ubuntu-os-cloud 는 첫 페이지 전체가 폐기본.
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
        // 서명 변경 시 이미지 선택지 구성이 무음으로 빈 목록 반환.
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
