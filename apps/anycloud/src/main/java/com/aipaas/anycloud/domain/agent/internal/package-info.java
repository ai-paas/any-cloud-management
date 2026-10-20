/**
 * agent 도메인의 내부 구현 — service impl + helper class.
 *
 * <p><b>외부 import 금지 (package-private 의도)</b>. 같은 도메인의 web/ 또는 root 에서만 의존.
 * 다른 도메인은 이 도메인의 root level service interface 만 지나갑니다.
 *
 * <p>Java module 시스템을 쓰지 않아 컴파일러가 강제하지 않습니다. ArchUnit (DomainArchitectureTest) 와 PR
 * review 가 enforce.
 */
package com.aipaas.anycloud.domain.agent.internal;
