package com.aipaas.anycloud.domain.addon.model;

/** RabbitMQ payload — addon install/uninstall request. */
public record AddonWorkflowMessage(String clusterId, String addonId, String operationId, String requestId) {}
