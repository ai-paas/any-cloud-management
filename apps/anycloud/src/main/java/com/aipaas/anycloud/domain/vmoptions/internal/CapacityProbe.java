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

    /** 검증용 클러스터 기준. 용량 조회는 크기를 함께 물어야 답이 달라진다. */
    private static final int PROBE_OCPUS = 2;

    private static final int PROBE_MEMORY_GB = 16;

    /** 한 리전에서 자리를 찾느라 목록 전체를 두드리지 않는다. */
    private static final int CANDIDATE_LIMIT = 8;

    private final CspCredentialService credentialService;
    private final OciVmOptionsProvider ociVmOptionsProvider;

    /** 고른 타입과, 못 고른 이유. 둘 중 하나만 채워진다. */
    public record SpecChoice(String specId, String blockedReason) {}

    public SpecChoice firstWithCapacity(
            SupportedProvisioningProvider provider, String credentialId, String region, List<String> candidates) {
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
                                credentials, region, candidate, PROBE_OCPUS, PROBE_MEMORY_GB)
                        .isPresent()) {
                    return new SpecChoice(candidate, null);
                }
            } catch (RuntimeException e) {
                // 용량 조회가 막혀도 생성을 막을 이유는 없다. 목록의 첫 값으로 간다.
                log.warn("OCI 용량 조회 실패 shape={}: {}", candidate, e.toString());
                return new SpecChoice(candidates.get(0), null);
            }
        }
        return new SpecChoice(null, region + " 에 자리가 없습니다. 확인한 타입 " + scanned.size() + "개 모두 용량 부족입니다.");
    }
}
