package com.aipaas.anycloud.domain.vmoptions.providers;

import com.aipaas.anycloud.domain.vmoptions.api.VmOptionImage;
import com.aipaas.anycloud.domain.vmoptions.api.VmOptionProvider;
import com.aipaas.anycloud.domain.vmoptions.api.VmOptionRegion;
import com.aipaas.anycloud.domain.vmoptions.api.VmOptionSpec;
import java.util.Comparator;
import java.util.List;
import org.springframework.util.StringUtils;

public abstract class CuratedVmOptionsProvider extends AbstractVmOptionsProvider {

    protected abstract String notes();

    protected abstract List<VmOptionRegion> regions();

    protected abstract List<VmOptionSpec> specs();

    protected abstract List<VmOptionImage> images();

    @Override
    public VmOptionProvider describe() {
        return describe(getProvider(), false, notes());
    }

    @Override
    public List<VmOptionRegion> listRegions() {
        return regions().stream()
                .sorted(Comparator.comparing(VmOptionRegion::getId))
                .toList();
    }

    @Override
    public List<VmOptionSpec> listSpecs(String region, String keyword, boolean gpuOnly, int limit) {
        return specs().stream()
                .filter(spec -> matchesRegion(spec.getRegion(), region))
                .filter(spec -> matchesKeyword(spec.getName(), keyword) || matchesKeyword(spec.getFamily(), keyword))
                .filter(spec -> !gpuOnly || (spec.getGpuCount() != null && spec.getGpuCount() > 0))
                .limit(limit)
                .toList();
    }

    @Override
    public List<VmOptionImage> listImages(String region, String keyword, String architecture, String owner, int limit) {
        return images().stream()
                .filter(image -> matchesRegion(image.getRegion(), region))
                .filter(image ->
                        matchesKeyword(image.getName(), keyword) || matchesKeyword(image.getOsVersion(), keyword))
                .filter(image -> matchesExactOrBlank(image.getArchitecture(), architecture))
                .filter(image -> matchesExactOrBlank(image.getOwner(), owner))
                .limit(limit)
                .toList();
    }

    protected VmOptionRegion region(String id) {
        return VmOptionRegion.builder()
                .provider(getProvider().getCanonicalName())
                .id(id)
                .name(id)
                .available(true)
                .build();
    }

    protected VmOptionSpec spec(
            String region,
            String id,
            String family,
            int vcpu,
            double memoryGb,
            int gpuCount,
            String architecture,
            String description) {
        return VmOptionSpec.builder()
                .provider(getProvider().getCanonicalName())
                .region(region)
                .id(id)
                .name(id)
                .family(family)
                .vcpu(vcpu)
                .memoryGb(memoryGb)
                .gpuCount(gpuCount)
                .architecture(architecture)
                .description(description)
                .available(true)
                .build();
    }

    protected VmOptionImage image(
            String region,
            String id,
            String name,
            String osType,
            String osVersion,
            String architecture,
            String owner,
            String visibility) {
        return VmOptionImage.builder()
                .provider(getProvider().getCanonicalName())
                .region(region)
                .id(id)
                .name(name)
                .osType(osType)
                .osVersion(osVersion)
                .architecture(architecture)
                .owner(owner)
                .visibility(visibility)
                .createdAt(null)
                .build();
    }

    private boolean matchesRegion(String candidate, String requested) {
        return !StringUtils.hasText(requested) || (candidate != null && candidate.equalsIgnoreCase(requested));
    }

    private boolean matchesExactOrBlank(String candidate, String requested) {
        return !StringUtils.hasText(requested) || (candidate != null && candidate.equalsIgnoreCase(requested));
    }
}
