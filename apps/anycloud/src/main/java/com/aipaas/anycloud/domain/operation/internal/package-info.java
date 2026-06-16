/**
 * operation 도메인의 내부 구현 — service impl + helper class.
 *
 * <p><b>외부 import 금지 (package-private 의도)</b>. 같은 도메인의 web/ 또는 root 에서만 의존.
 * 다른 도메인은 본 도메인의 root level service interface 만 통과.
 *
 * <p>Java module 시스템 미사용 — 컴파일러 강제 없음. ArchUnit (DomainArchitectureTest) 와 PR
 * review 가 enforce.
 */
package com.aipaas.anycloud.domain.operation.internal;
