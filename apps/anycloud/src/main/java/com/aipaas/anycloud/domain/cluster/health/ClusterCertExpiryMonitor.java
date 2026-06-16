package com.aipaas.anycloud.domain.cluster.health;

import com.aipaas.anycloud.domain.cluster.ClusterEntity;
import com.aipaas.anycloud.domain.cluster.ClusterRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import jakarta.annotation.PostConstruct;
import java.io.ByteArrayInputStream;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 등록된 클러스터의 인증서 만료까지 남은 일수를 주기적으로 측정해 Prometheus 로 노출.
 * <p>
 * Day-2 §5. kubeadm 기본 client cert 는 1년 유효라 주기 갱신이 필요한데, 알람 없이는
 * 만료 후에야 발견된다. anycloud 가 등록한 ClusterEntity 의 server_ca / client_ca 를
 * X.509 파싱해 NotAfter 까지의 잔여 일수를 metric 으로 송출한다.
 * <p>
 * Metric:
 * <pre>anycloud_cluster_cert_expiry_days{cluster="...", cert="serverCa|clientCa"}</pre>
 * <p>
 * 운영 알람 예: {@code anycloud_cluster_cert_expiry_days < 30}
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ClusterCertExpiryMonitor {

    private static final int WARN_THRESHOLD_DAYS = 30;
    /** 한 페이지 사이즈. cluster 수가 많아도 메모리 / DB 부하 일정하게 유지. */
    private static final int CHUNK_SIZE = 50;
    /** 페이지 간 짧은 sleep — DB / K8s API 에 spike 가 안 가도록. */
    private static final long INTER_CHUNK_SLEEP_MS = 1_000;

    private final ClusterRepository clusterRepository;
    private final MeterRegistry meterRegistry;

    private MultiGauge expiryGauge;

    @PostConstruct
    void init() {
        this.expiryGauge = MultiGauge.builder("anycloud.cluster.cert.expiry.days")
                .description("Days until cluster certificate expiry (negative=expired, 0 unparsed/missing)")
                .baseUnit("days")
                .register(meterRegistry);
    }

    /**
     * 매일 02:00 UTC. 운영 환경에선 cron 을 override 해 시간대 / 빈도 조정 가능.
     * <p>
     * cluster 수가 많을 때 (1000+) {@code findAll()} 한 번에 메모리에 올리지 않도록 50개씩
     * 페이지 처리. 페이지 간 1초 sleep — DB 와 K8s API 양쪽 spike 방지. metric 은 마지막
     * register 시에만 atomic 갱신 (이전 cluster 의 row 가 사라지지 않도록 cumulative).
     */
    @Scheduled(cron = "${cluster.cert.expiry-check.cron:0 0 2 * * *}")
    @SchedulerLock(name = "certExpiryScan", lockAtMostFor = "PT1H", lockAtLeastFor = "PT5M")
    public void scan() {
        List<MultiGauge.Row<?>> allRows = new ArrayList<>();
        int totalProcessed = 0;
        int page = 0;

        while (true) {
            Page<ClusterEntity> chunk = clusterRepository.findAll(PageRequest.of(page, CHUNK_SIZE));
            if (chunk.isEmpty()) {
                break;
            }

            for (ClusterEntity cluster : chunk.getContent()) {
                // serverCa / clientCa 컬럼 제거 — backend 가 cert 만료를 추적하지 않음.
                // Agent 가 in-cluster cert 만료 (kubelet, API server cert) 를 자체 모니터링 — 다음 sprint
                // 에서 agent → backend metric push 로 통합. 그동안 본 monitor 는 no-op.
                allRows.add(MultiGauge.Row.of(Tags.of("cluster", cluster.getId(), "cert", "deprecated-H48"), -1L));
            }

            totalProcessed += chunk.getNumberOfElements();
            page++;
            if (!chunk.hasNext()) {
                break;
            }
            try {
                Thread.sleep(INTER_CHUNK_SLEEP_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Cert scan interrupted at page {}", page);
                break;
            }
        }

        // 전체 row 를 한 번에 register (이전 scan 의 row 는 자동 expire).
        expiryGauge.register(allRows, true);
        log.info("Cluster cert expiry scan complete: {} clusters in {} pages", totalProcessed, page);
    }

    /**
     * Base64 로 wrap 된 PEM 또는 plain PEM 모두 시도해 X.509 파싱.
     * 실패 / 비어 있으면 -1 반환(metric 에서 unknown 으로 식별 가능).
     */
    private long expiryDays(String base64OrPem) {
        if (base64OrPem == null || base64OrPem.isBlank()) {
            return -1;
        }
        byte[] der = decode(base64OrPem.trim());
        if (der == null) {
            return -1;
        }
        try {
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            X509Certificate cert = (X509Certificate) factory.generateCertificate(new ByteArrayInputStream(der));
            Duration remaining =
                    Duration.between(Instant.now(), cert.getNotAfter().toInstant());
            return remaining.toDays();
        } catch (CertificateException e) {
            return -1;
        }
    }

    private byte[] decode(String value) {
        // 1) 그대로 PEM 인 경우
        if (value.contains("-----BEGIN")) {
            return value.getBytes();
        }
        // 2) Base64 wrap 된 PEM 인 경우
        try {
            byte[] decoded = Base64.getDecoder().decode(value);
            // PEM 이 base64 인코딩 후 다시 base64 wrap 됐을 가능성
            return decoded;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
