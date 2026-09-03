package com.aipaas.anycloud.domain.addon.model;

/**
 * RabbitMQ payload — addon install/uninstall request.
 *
 * <p>payload 는 식별자만 (clusterId / addonId) — listener 가 DB 에서 latest
 * spec 을 re-fetch. queue 잔류 중 spec 이 변경되어도 latest 적용 (예: chartVersion patch).
 * operationId 는 LRO progress tracking 용.
 *
 * <p>Jackson record support — default constructor 불필요.
 */
public record AddonWorkflowMessage(String clusterId, String addonId, String operationId, String requestId) {}
