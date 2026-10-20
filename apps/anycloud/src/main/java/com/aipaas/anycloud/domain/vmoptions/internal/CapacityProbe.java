package com.aipaas.anycloud.domain.vmoptions.internal;

import com.aipaas.anycloud.domain.credential.CspCredentialService;
import com.aipaas.anycloud.domain.provisioning.model.SupportedProvisioningProvider;
import com.aipaas.anycloud.domain.vmoptions.providers.OciVmOptionsProvider;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 선택한 인스턴스 타입의 리전 내 생성 가능 여부 확인.
 *
 * <p>대부분의 CSP 는 목록에 있으면 생성 가능. OCI 는 목록에 있어도 용량이 없을 수 있어 별도
 * 조회. 미확인 시 VCN 과 서브넷 생성 후 인스턴스 단계에서 실패하고 롤백.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CapacityProbe {

    /** 한 리전에서 자리를 찾느라 목록 전체를 두드리지 않는다. */
    private static final int CANDIDATE_LIMIT = 8;

    private final CspCredentialService credentialService;
    private final OciVmOptionsProvider ociVmOptionsProvider;

    /** 고른 타입과, 못 고른 이유. 둘 중 하나만 채워진다. */
    public record SpecChoice(String specId, String blockedReason) {}

    /**
     * @param filter 요청한 최소 사양. Flex shape 은 크기별로 가용 여부가 달라 요청값 그대로
     *     조회 필요. 고정 크기로 조회하면 가용한 용량을 없음으로 응답
     */
    public SpecChoice firstWithCapacity(
            SupportedProvisioningProvider provider,
            String credentialId,
            String region,
            List<String> candidates,
            com.aipaas.anycloud.domain.vmoptions.api.SpecFilter filter) {
        if (candidates.isEmpty()) {
            return new SpecChoice(null, "고를 수 있는 인스턴스 타입이 없습니다.");
        }
        if (provider != SupportedProvisioningProvider.OCI) {
            return new SpecChoice(candidates.get(0), null);
        }
        Map<String, String> credentials =
                credentialService.resolveEnvironment(provider.getCanonicalName(), credentialId);
        List<String> scanned = candidates.stream().limit(CANDIDATE_LIMIT).toList();
        for (String candidate : scanned) {
            try {
                if (ociVmOptionsProvider
                        .findAvailabilityDomainWithCapacity(
                                credentials, region, candidate, filter.minVcpu(), (int) Math.ceil(filter.minMemoryGb()))
                        .isPresent()) {
                    return new SpecChoice(candidate, null);
                }
            } catch (RuntimeException e) {
                // 용량 조회가 막혀도 생성을 막을 이유는 없다. 목록의 첫 값으로 간다.
                log.warn("OCI 용량 조회 실패 shape={}: {}", candidate, e.toString());
                return new SpecChoice(candidates.get(0), null);
            }
        }
        // 조회한 타입과 크기를 메시지에 포함. 없으면 운영자가 다음 조치를 판단할 수 없음.
        return new SpecChoice(
                null,
                region + " 에 자리가 없습니다. " + filter.minVcpu() + "vCPU "
                        + (int) Math.ceil(filter.minMemoryGb()) + "GB 기준으로 확인한 타입 "
                        + String.join(", ", scanned) + " 모두 용량 부족입니다.");
    }
}
