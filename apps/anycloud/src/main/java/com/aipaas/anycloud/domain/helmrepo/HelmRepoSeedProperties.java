package com.aipaas.anycloud.domain.helmrepo;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 부팅 시 외부 public helm repo 들을 자동 등록 (멱등 — 이름 충돌 시 skip).
 *
 * <p>{@code helm-repo.auto-seed.enabled=true} (default) 이면 {@link #repos} 의 entry 들이 DB 에
 * 등록됨. 사용자가 수동으로 같은 이름 등록한 경우 그대로 두고 skip — 사용자 입력 우선.
 *
 * <p>설정 예 (application.yaml):
 * <pre>
 * helm-repo:
 *   auto-seed:
 *     enabled: true
 *     repos:
 *       - name: prometheus-community
 *         url: https://prometheus-community.github.io/helm-charts
 *         tags: monitoring,seeded
 * </pre>
 *
 * <p>Air-gapped 환경에서는 {@code enabled=false} 설정 후 internal ChartMuseum 만 등록.
 */
@ConfigurationProperties(prefix = "helm-repo.auto-seed")
public record HelmRepoSeedProperties(boolean enabled, List<Seed> repos) {

    public HelmRepoSeedProperties {
        if (repos == null) {
            repos = new ArrayList<>();
        }
    }

    /** Seed entry — name + URL 만 필수. 나머지 nullable. */
    public record Seed(
            String name, String url, String username, String password, Boolean insecureSkipTlsVerify, String tags) {

        public Seed {
            if (insecureSkipTlsVerify == null) {
                insecureSkipTlsVerify = false;
            }
        }
    }
}
