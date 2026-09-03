package com.aipaas.anycloud.domain.helmrepo.model;

/**
 * — Helm repo CRUD 후 transaction commit 이후에 발행되는 event.
 *
 * <p>{@code ApplicationEventPublisher.publishEvent} 로 발행 후 {@code @TransactionalEventListener
 * (phase = AFTER_COMMIT)} listener 가 받아 처리 — async broadcast 가 새 row 를 볼 수 있도록 보장.
 */
public record HelmRepoChangedEvent(String repoName, String operation) {}
