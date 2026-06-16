package com.aipaas.anycloud.domain.operation.model;

/**
 * Operation 의 단순 5-state machine.
 * <pre>
 *   PENDING ──▶ RUNNING ──┬──▶ SUCCEEDED
 *                          ├──▶ FAILED
 *                          └──▶ CANCELLED
 * </pre>
 */
public enum OperationState {
    PENDING,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED;

    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED;
    }
}
