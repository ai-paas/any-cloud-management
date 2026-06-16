package com.aipaas.anycloud.domain.provisioning.pricing;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** PriceCatalog + CostEstimator smoke 검증. */
class CostEstimatorTest {

    private final PriceCatalog catalog = new PriceCatalog();
    private final CostEstimator estimator = new CostEstimator(catalog);

    @Test
    void catalogLoadsAllEightProviders() {
        assertThat(catalog.supportedProviders())
                .containsExactlyInAnyOrder(
                        "aws", "gcp", "azure", "alibaba", "oci", "digitalocean", "openstack", "proxmox");
    }

    @Test
    void awsT3LargeIsKnown() {
        assertThat(catalog.lookup("aws", "t3.large")).isPresent();
        assertThat(catalog.lookup("aws", "t3.large").get().hourly()).isEqualByComparingTo(new BigDecimal("0.0832"));
    }

    @Test
    void unknownProviderReturnsStatusUnknown() {
        CostEstimate est = estimator.estimate(
                "BOGUS_PROVIDER",
                Map.of("masterInstanceType", "x", "workerInstanceType", "y", "workerCount", "2"),
                false);
        assertThat(est.status()).isEqualTo(CostEstimate.Status.UNKNOWN);
        assertThat(est.totalHourly()).isNull();
    }

    @Test
    void pulumiNamespacePrefixedKeysAlsoWork() {
        // VmClusterPreflight 가 보내는 실제 형식 — `anycloud-k8s:` prefix 포함.
        CostEstimate est = estimator.estimate(
                "aws",
                Map.of(
                        "anycloud-k8s:masterInstanceType", "t3.large",
                        "anycloud-k8s:workerInstanceType", "t3.large",
                        "anycloud-k8s:masterCount", "1",
                        "anycloud-k8s:workerCount", "2"),
                false);
        assertThat(est.status()).isEqualTo(CostEstimate.Status.FULL);
        // 같은 instance type, 같은 count → 같은 totalHourly.
        assertThat(est.totalHourly()).isEqualByComparingTo(new BigDecimal("0.2496"));
        // breakdown 에 instanceType 이 채워져야 한다.
        assertThat(est.breakdown().get(0).instanceType()).isEqualTo("t3.large");
        assertThat(est.breakdown().get(1).instanceType()).isEqualTo("t3.large");
    }

    @Test
    void proxmoxBareMetalCalculatesViaTcoBaseline() {
        // proxmox-standard-4x8: 0.024/hr; 1 master + 2 workers = 0.072/hr
        CostEstimate est = estimator.estimate(
                "proxmox",
                Map.of(
                        "masterInstanceType", "proxmox-standard-4x8",
                        "workerInstanceType", "proxmox-standard-4x8",
                        "masterCount", "1",
                        "workerCount", "2"),
                false);
        assertThat(est.status()).isEqualTo(CostEstimate.Status.FULL);
        assertThat(est.totalHourly()).isEqualByComparingTo(new BigDecimal("0.0720"));
    }

    @Test
    void fullEstimateForKnownAwsCluster() {
        CostEstimate est = estimator.estimate(
                "aws",
                Map.of(
                        "masterInstanceType", "t3.large",
                        "workerInstanceType", "t3.large",
                        "masterCount", "1",
                        "workerCount", "2"),
                false);
        assertThat(est.status()).isEqualTo(CostEstimate.Status.FULL);
        // 1 × 0.0832 + 2 × 0.0832 = 0.2496
        assertThat(est.totalHourly()).isEqualByComparingTo(new BigDecimal("0.2496"));
        // 30 일 × 24 시간 × 0.2496 = 179.71
        assertThat(est.monthlyProjection()).isEqualByComparingTo(new BigDecimal("179.71"));
        assertThat(est.spotApplied()).isFalse();
    }

    @Test
    void spotDiscountAppliedWhenRequested() {
        CostEstimate noSpot = estimator.estimate(
                "aws",
                Map.of(
                        "masterInstanceType", "t3.large",
                        "workerInstanceType", "t3.large",
                        "workerCount", "2"),
                false);
        CostEstimate withSpot = estimator.estimate(
                "aws",
                Map.of(
                        "masterInstanceType", "t3.large",
                        "workerInstanceType", "t3.large",
                        "workerCount", "2"),
                true);
        assertThat(withSpot.spotApplied()).isTrue();
        // spot 가격이 무조건 더 낮아야 함.
        assertThat(withSpot.totalHourly()).isLessThan(noSpot.totalHourly());
        // AWS catalog 의 spotDiscount=0.65 → 35% 만 청구.
        assertThat(withSpot.totalHourly())
                .isEqualByComparingTo(noSpot.totalHourly()
                        .multiply(new BigDecimal("0.35"))
                        .setScale(4, java.math.RoundingMode.HALF_UP));
    }

    @Test
    void unknownInstanceTypeBecomesPartial() {
        CostEstimate est = estimator.estimate(
                "aws",
                Map.of(
                        "masterInstanceType", "t3.large",
                        "workerInstanceType", "BOGUS_TYPE",
                        "workerCount", "2"),
                false);
        assertThat(est.status()).isEqualTo(CostEstimate.Status.PARTIAL);
        assertThat(est.breakdown()).hasSize(2);
        // worker line 은 unitHourly null.
        assertThat(est.breakdown().get(1).unitHourly()).isNull();
        // master line 만 가격이 합산됨.
        assertThat(est.totalHourly()).isEqualByComparingTo(new BigDecimal("0.0832"));
    }
}
