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
 * 고른 인스턴스 타입을 지금 그 리전에 띄울 수 있는지 확인한다.
 *
 * <p>대부분의 CSP 는 목록에 있으면 만들 수 있다. OCI 만 목록에 있어도 자리가 없을 수 있어
 * 따로 묻는다 — 확인하지 않으면 VCN 과 서브넷을 다 만든 뒤 인스턴스에서 실패하고 롤백한다.
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
     * @param filter 요청한 최소 사양. Flex shape 은 크기에 따라 가용 여부가 달라져 그대로 물어야
     *     한다. 고정 크기로 물으면 있는 자리를 없다고 답한다
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
        // 어떤 타입을 어떤 크기로 물었는지 없으면 다음 행동이 보이지 않는다.
        return new SpecChoice(
                null,
                region + " 에 자리가 없습니다. " + filter.minVcpu() + "vCPU "
                        + (int) Math.ceil(filter.minMemoryGb()) + "GB 기준으로 확인한 타입 "
                        + String.join(", ", scanned) + " 모두 용량 부족입니다.");
    }
}
