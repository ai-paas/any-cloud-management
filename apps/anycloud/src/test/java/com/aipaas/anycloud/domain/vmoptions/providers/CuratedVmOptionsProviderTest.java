package com.aipaas.anycloud.domain.vmoptions.providers;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipaas.anycloud.domain.provisioning.model.SupportedProvisioningProvider;
import com.aipaas.anycloud.domain.vmoptions.api.VmOptionImage;
import com.aipaas.anycloud.domain.vmoptions.api.VmOptionRegion;
import com.aipaas.anycloud.domain.vmoptions.api.VmOptionSpec;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 정적 카탈로그 provider 의 필터, 정렬 규칙.
 *
 * <p>region/keyword/gpuOnly/architecture/owner/limit 조합이 CSP 호출 없이 동작하는 유일한
 * 경로라, 여기가 provider 공통 필터 semantics 의 기준이 된다.
 */
class CuratedVmOptionsProviderTest {

    private static final class Fixture extends CuratedVmOptionsProvider {
        @Override
        public SupportedProvisioningProvider getProvider() {
            return SupportedProvisioningProvider.OPENSTACK;
        }

        @Override
        protected String notes() {
            return "test fixture";
        }

        @Override
        protected List<VmOptionRegion> regions() {
            return List.of(region("regionTwo"), region("regionOne"));
        }

        @Override
        protected List<VmOptionSpec> specs() {
            return List.of(
                    spec("regionOne", "m1.small", "m1", 2, 4.0, 0, "x86_64", "general"),
                    spec("regionOne", "g1.gpu", "g1", 8, 32.0, 2, "x86_64", "gpu node"),
                    spec("regionTwo", "m1.large", "m1", 4, 8.0, 0, "arm64", "general"));
        }

        @Override
        protected List<VmOptionImage> images() {
            return List.of(
                    image("regionOne", "img-1", "ubuntu-22.04", "linux", "22.04", "x86_64", "canonical", "public"),
                    image("regionOne", "img-2", "windows-2022", "windows", "2022", "x86_64", "microsoft", "public"),
                    image("regionTwo", "img-3", "ubuntu-24.04", "linux", "24.04", "arm64", "canonical", "public"));
        }
    }

    private final Fixture p = new Fixture();

    @Test
    @DisplayName("region 목록은 id 오름차순으로 정렬한다")
    void listRegions_sortedById() {
        assertThat(p.listRegions()).extracting(VmOptionRegion::getId).containsExactly("regionOne", "regionTwo");
    }

    @Test
    @DisplayName("region 이 비면 전체, 지정하면 해당 region 만")
    void listSpecs_regionFilter() {
        assertThat(p.listSpecs(null, null, false, 100)).hasSize(3);
        assertThat(p.listSpecs("regionOne", null, false, 100))
                .extracting(VmOptionSpec::getId)
                .containsExactlyInAnyOrder("m1.small", "g1.gpu");
    }

    @Test
    @DisplayName("region 매칭은 대소문자를 구분하지 않는다")
    void listSpecs_regionCaseInsensitive() {
        assertThat(p.listSpecs("REGIONONE", null, false, 100)).hasSize(2);
    }

    @Test
    @DisplayName("keyword 는 name 또는 family 중 하나만 맞아도 통과한다")
    void listSpecs_keywordMatchesNameOrFamily() {
        assertThat(p.listSpecs(null, "g1", false, 100))
                .extracting(VmOptionSpec::getId)
                .containsExactly("g1.gpu");
        assertThat(p.listSpecs(null, "m1", false, 100)).hasSize(2);
    }

    @Test
    @DisplayName("gpuOnly 는 gpuCount 가 1 이상인 것만 남긴다")
    void listSpecs_gpuOnly() {
        assertThat(p.listSpecs(null, null, true, 100))
                .extracting(VmOptionSpec::getId)
                .containsExactly("g1.gpu");
    }

    @Test
    @DisplayName("limit 는 필터 이후에 적용한다")
    void listSpecs_limitAppliedAfterFilter() {
        assertThat(p.listSpecs("regionOne", null, false, 1)).hasSize(1);
        assertThat(p.listSpecs(null, null, false, 0)).isEmpty();
    }

    @Test
    @DisplayName("image keyword 는 name 또는 osVersion 에 걸린다")
    void listImages_keywordMatchesNameOrOsVersion() {
        assertThat(p.listImages(null, "ubuntu", null, null, 100)).hasSize(2);
        assertThat(p.listImages(null, "24.04", null, null, 100))
                .extracting(VmOptionImage::getId)
                .containsExactly("img-3");
    }

    @Test
    @DisplayName("architecture 와 owner 는 정확히 일치해야 한다 (비면 통과)")
    void listImages_exactOrBlankFilters() {
        assertThat(p.listImages(null, null, "arm64", null, 100))
                .extracting(VmOptionImage::getId)
                .containsExactly("img-3");
        assertThat(p.listImages(null, null, null, "microsoft", 100))
                .extracting(VmOptionImage::getId)
                .containsExactly("img-2");
        // 부분 문자열은 걸리지 않는다 — keyword 와 다른 규칙
        assertThat(p.listImages(null, null, null, "canon", 100)).isEmpty();
        assertThat(p.listImages(null, null, null, null, 100)).hasSize(3);
    }

    @Test
    @DisplayName("describe 는 provider 이름과 notes 를 담는다")
    void describe_carriesNotes() {
        assertThat(p.describe().getNotes()).isEqualTo("test fixture");
    }
}
