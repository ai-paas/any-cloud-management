package com.aipaas.anycloud.domain.helmrepo.internal;

import com.aipaas.anycloud.domain.helmrepo.HelmRepoEntity;
import com.aipaas.anycloud.domain.helmrepo.HelmRepoRepository;
import com.aipaas.anycloud.domain.helmrepo.HelmRepoSeedProperties;
import com.aipaas.anycloud.domain.helmrepo.model.HelmRepoSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * ApplicationReady 시점에 {@link HelmRepoSeedProperties#repos} 에 정의된
 * 외부 public helm repo 들을 DB 에 자동 등록.
 *
 * <p>멱등 — name 충돌 시 skip (사용자 수동 등록 우선). 부팅마다 매번 실행되어도 안전.
 *
 * <p>Disable: {@code helm-repo.auto-seed.enabled=false} (air-gapped 환경).
 */
@Slf4j
@Component
@RequiredArgsConstructor
@EnableConfigurationProperties(HelmRepoSeedProperties.class)
@ConditionalOnProperty(name = "helm-repo.auto-seed.enabled", havingValue = "true", matchIfMissing = true)
public class HelmRepoAutoSeedRunner {

    private final HelmRepoSeedProperties seedProperties;
    private final HelmRepoRepository helmRepoRepository;

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void seedOnReady() {
        if (seedProperties.repos().isEmpty()) {
            log.info("HelmRepo auto-seed: no entries configured — skipping");
            return;
        }
        int created = 0;
        int skipped = 0;
        for (HelmRepoSeedProperties.Seed seed : seedProperties.repos()) {
            if (seed.name() == null
                    || seed.name().isBlank()
                    || seed.url() == null
                    || seed.url().isBlank()) {
                log.warn("HelmRepo auto-seed: skip invalid entry name={} url={}", seed.name(), seed.url());
                continue;
            }
            if (helmRepoRepository.existsByName(seed.name())) {
                skipped++;
                continue;
            }
            HelmRepoEntity entity = HelmRepoEntity.builder()
                    .name(seed.name())
                    .url(seed.url())
                    .username(seed.username())
                    .password(seed.password())
                    .insecureSkipTlsVerify(Boolean.TRUE.equals(seed.insecureSkipTlsVerify()))
                    .source(HelmRepoSource.EXTERNAL)
                    .tags(seed.tags() == null ? "seeded" : seed.tags() + ",seeded")
                    .build();
            try {
                helmRepoRepository.save(entity);
                created++;
                log.info("HelmRepo auto-seed: created name={} url={}", seed.name(), seed.url());
            } catch (Exception e) {
                log.warn("HelmRepo auto-seed: failed name={} url={}: {}", seed.name(), seed.url(), e.toString());
            }
        }
        log.info(
                "HelmRepo auto-seed: completed — created={}, skipped(existing)={}, total={}",
                created,
                skipped,
                seedProperties.repos().size());
    }
}
