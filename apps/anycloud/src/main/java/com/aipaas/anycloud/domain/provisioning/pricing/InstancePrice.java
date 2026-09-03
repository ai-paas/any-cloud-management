package com.aipaas.anycloud.domain.provisioning.pricing;

import java.math.BigDecimal;

/**
 * 단일 instance type 의 가격.
 *
 * @param hourly   USD/hour. on-demand list price 기준.
 * @param vcpu     vCPU 개수 (UI 표시용).
 * @param memoryGb memory GB (UI 표시용).
 * @param gpu      GPU 개수 (없으면 0).
 */
public record InstancePrice(BigDecimal hourly, int vcpu, int memoryGb, int gpu) {}
