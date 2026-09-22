package com.aipaas.anycloud.domain.credential.internal;

import com.aipaas.anycloud.common.error.exception.CustomException;
import com.aipaas.anycloud.common.error.exception.provisioning.CredentialFailureKind;
import com.aipaas.anycloud.domain.credential.CredentialHealth;
import com.aipaas.anycloud.domain.credential.CredentialHealthService;
import com.aipaas.anycloud.domain.credential.CredentialVerification;
import com.aipaas.anycloud.domain.credential.CspCredentialEntity;
import com.aipaas.anycloud.domain.credential.CspCredentialRepository;
import com.aipaas.anycloud.domain.credential.CspCredentialService;
import com.aipaas.anycloud.domain.provisioning.proxmox.ProxmoxApiClient;
import com.aipaas.anycloud.domain.vmoptions.VmOptionsService;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 리전 조회로 자격증명을 확인한다.
 *
 * <p>등록만 하고 쓸 수 있는지는 프로비저닝을 걸어봐야 알았다. 리전 조회는 CSP API 를 실제로
 * 부르므로 그 자체가 검증이다 — 비밀값을 내보내지 않으므로 새 노출면이 생기지 않는다.
 *
 * <p>결과는 저장한다. 화면 상태로만 두면 새로고침하면 사라지고 사용자마다 각자 확인해야 한다.
 * 반대로 페이지를 열 때마다 전부 다시 확인하면 CSP 요청 제한에 걸리므로, 자동 갱신은 오래된
 * 것만 본다. 사용자가 직접 누른 확인은 캐시를 건너뛴다 — 방금 키를 고쳤을 수 있다.
 */
@Slf4j
@Service
public class CredentialHealthServiceImpl implements CredentialHealthService {

    private static final String HEALTHY = "HEALTHY";
    private static final String UNHEALTHY = "UNHEALTHY";
    /** 확인할 방법이 없어 모른다. 실패와 구분해야 한다 — 정상인 자격증명을 실패로 보이면 경고를 무시하게 된다. */
    private static final String UNKNOWN = "UNKNOWN";

    private static final int DETAIL_MAX = 1000;

    private final VmOptionsService vmOptionsService;
    private final CspCredentialRepository credentialRepository;
    private final CspCredentialService credentialService;
    private final ProxmoxApiClient proxmoxApiClient;
    private final Duration freshFor;

    public CredentialHealthServiceImpl(
            VmOptionsService vmOptionsService,
            CspCredentialRepository credentialRepository,
            CspCredentialService credentialService,
            ProxmoxApiClient proxmoxApiClient,
            @Value("${anycloud.credential.health-fresh-for:PT10M}") Duration freshFor) {
        this.vmOptionsService = vmOptionsService;
        this.credentialRepository = credentialRepository;
        this.credentialService = credentialService;
        this.proxmoxApiClient = proxmoxApiClient;
        this.freshFor = freshFor;
    }

    @Override
    @Transactional
    public CredentialHealth check(String provider, String credentialId) {
        CredentialHealth health = probe(provider, credentialId);
        credentialRepository.findById(credentialId).ifPresent(entity -> store(entity, health));
        return health;
    }

    @Override
    @Transactional
    public CredentialHealth refreshIfStale(String provider, String credentialId) {
        Optional<CspCredentialEntity> found = credentialRepository.findById(credentialId);
        if (found.isPresent() && isFresh(found.get())) {
            return stored(found.get());
        }
        return check(provider, credentialId);
    }

    private boolean isFresh(CspCredentialEntity entity) {
        LocalDateTime checkedAt = entity.getHealthCheckedAt();
        return checkedAt != null && checkedAt.isAfter(LocalDateTime.now().minus(freshFor));
    }

    private CredentialHealth stored(CspCredentialEntity entity) {
        boolean healthy = HEALTHY.equals(entity.getHealthStatus());
        String kind = entity.getHealthKind();
        return new CredentialHealth(
                healthy,
                kind,
                kind == null ? null : CredentialFailureKind.valueOf(kind).hint(),
                entity.getHealthDetail(),
                entity.getHealthCheckedRegions() == null ? 0 : entity.getHealthCheckedRegions(),
                entity.getHealthCheckedAt());
    }

    private void store(CspCredentialEntity entity, CredentialHealth health) {
        entity.setHealthStatus(statusOf(health));
        entity.setHealthKind(health.kind());
        entity.setHealthDetail(trim(health.detail()));
        entity.setHealthCheckedAt(LocalDateTime.now());
        entity.setHealthCheckedRegions(health.checkedRegions());
        credentialRepository.save(entity);
    }

    private static String statusOf(CredentialHealth health) {
        if (health.healthy()) {
            return HEALTHY;
        }
        return CredentialFailureKind.NOT_VERIFIABLE.name().equals(health.kind()) ? UNKNOWN : UNHEALTHY;
    }

    private String trim(String detail) {
        if (detail == null) {
            return null;
        }
        return detail.length() <= DETAIL_MAX ? detail : detail.substring(0, DETAIL_MAX);
    }

    private CredentialHealth probe(String provider, String credentialId) {
        if ("Proxmox".equalsIgnoreCase(provider)) {
            return probeProxmox(credentialId);
        }
        try {
            int regions = vmOptionsService.getRegions(provider, credentialId).size();
            if (regions == 0) {
                // 호출은 성공했는데 아무것도 못 보는 자격증명은 쓸 수 없다.
                return new CredentialHealth(
                        false,
                        CredentialFailureKind.PERMISSION_DENIED.name(),
                        CredentialFailureKind.PERMISSION_DENIED.hint(),
                        "조회된 리전이 없습니다.",
                        0,
                        LocalDateTime.now());
            }
            // 리전 목록이 정적인 프로바이더는 자격증명을 건드리지도 않는다. 그걸 정상이라 말하면
            // 완전히 가짜인 키가 '정상'으로 나온다 — 실제로 그랬다.
            if (!CredentialVerification.verifiable(provider)) {
                return new CredentialHealth(
                        false,
                        CredentialFailureKind.NOT_VERIFIABLE.name(),
                        CredentialFailureKind.NOT_VERIFIABLE.hint(),
                        null,
                        0,
                        LocalDateTime.now());
            }
            return new CredentialHealth(true, null, null, null, regions, LocalDateTime.now());
        } catch (Exception e) {
            String raw = raw(e);
            CredentialFailureKind kind = CredentialFailureKind.from(raw);
            log.info("자격증명 확인 실패 provider={} credentialId={} kind={}", provider, credentialId, kind);
            return new CredentialHealth(false, kind.name(), kind.hint(), raw, 0, LocalDateTime.now());
        }
    }

    /**
     * Proxmox 는 리전이 없어 리전 조회로 확인할 수 없다.
     *
     * <p>{@code getRegions} 로 내려가면 등록된 VM options provider 가 없어 예외로 끝나고, 정상인
     * 자격증명도 "확인 불가" 로 보인다. {@code /version} 은 토큰을 실제로 검사하므로 그 호출이
     * 곧 검증이다 — 만료된 토큰까지 걸러진다.
     */
    private CredentialHealth probeProxmox(String credentialId) {
        try {
            Map<String, String> env = credentialService
                    .resolveForProvision("Proxmox", credentialId)
                    .environmentOrEmpty();
            JsonNode version = proxmoxApiClient.version(env);
            if (version == null || version.path("version").asText("").isBlank()) {
                return new CredentialHealth(
                        false,
                        CredentialFailureKind.UPSTREAM_UNAVAILABLE.name(),
                        CredentialFailureKind.UPSTREAM_UNAVAILABLE.hint(),
                        "PVE API 에 닿지 못했습니다.",
                        0,
                        LocalDateTime.now());
            }
            // 리전이 없는 프로바이더라 개수는 0 이다. 확인한 것은 토큰이 통한다는 사실이다.
            return new CredentialHealth(true, null, null, null, 0, LocalDateTime.now());
        } catch (Exception e) {
            String raw = raw(e);
            CredentialFailureKind kind = CredentialFailureKind.from(raw);
            log.info("Proxmox 자격증명 확인 실패 credentialId={} kind={}", credentialId, kind);
            return new CredentialHealth(false, kind.name(), kind.hint(), raw, 0, LocalDateTime.now());
        }
    }

    /** 분류에 쓸 원문. CustomException 은 reason 에 CSP 응답이 들어 있다. */
    private String raw(Exception e) {
        if (e instanceof CustomException ce && ce.getReason() != null) {
            return ce.getReason();
        }
        return e.getMessage() == null ? e.toString() : e.getMessage();
    }
}
