package com.aipaas.anycloud.domain.vmoptions;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * AWS 리전 목록이 자격증명을 검증하도록 바꿨는지 고정한다.
 *
 * <p>{@code Region.regions()} 는 SDK 에 컴파일된 목록이라 AWS 를 호출조차 하지 않는다.
 * 완전히 가짜인 키가 '정상'으로 나왔다. DescribeRegions 는 인증이 필요한 호출이다.
 */
class AwsRegionVerificationTest extends AbstractUnitTest {

    private static final Path PROVIDER =
            Path.of("src/main/java/com/aipaas/anycloud/domain/vmoptions/providers/AwsVmOptionsProvider.java");

    private String source() throws IOException {
        return Files.readString(PROVIDER);
    }

    @Test
    void credentialAwareRegionListCallsTheCloud() throws IOException {
        assertThat(source()).contains("describeRegions");
    }

    @Test
    void theCompiledInRegionListIsNoLongerTheCredentialAwarePath() throws IOException {
        // listRegions(credentials) 가 Region.regions() 를 그대로 쓰면 검증이 되지 않는다.
        String src = source();
        int credentialAware = src.indexOf("listRegions(Map<String, String> credentials)");

        assertThat(credentialAware).as("credential-aware listRegions 가 없다").isGreaterThan(0);
        String body = src.substring(credentialAware, Math.min(src.length(), credentialAware + 600));
        assertThat(body).doesNotContain("Region.regions()");
    }
}
