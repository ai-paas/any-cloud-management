package com.aipaas.anycloud.domain.provisioning.convergence;

/**
 * probe 1회의 결과.
 *
 * @param detail NOT_READY / UNKNOWN 사유. 운영자에게 그대로 노출되므로 자격증명을 담지 않는다.
 */
public record ComponentProbe(ComponentHealth health, String detail) {

    public static ComponentProbe ready() {
        return new ComponentProbe(ComponentHealth.READY, null);
    }

    public static ComponentProbe notReady(String detail) {
        return new ComponentProbe(ComponentHealth.NOT_READY, detail);
    }

    public static ComponentProbe unknown(String detail) {
        return new ComponentProbe(ComponentHealth.UNKNOWN, detail);
    }
}
