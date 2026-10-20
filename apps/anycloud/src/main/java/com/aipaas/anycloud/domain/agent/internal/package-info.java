/**
 * agent 도메인 내부 구현. service impl 과 helper class.
 *
 * <p><b>외부 import 금지 (package-private 의도)</b>. 동일 도메인의 web/ 또는 root 에서만 참조.
 * 다른 도메인은 이 도메인의 root level service interface 만 사용.
 *
 * <p>Java module 시스템 미사용이라 컴파일러 강제 없음. ArchUnit (DomainArchitectureTest) 와
 * PR review 로 강제.
 */
package com.aipaas.anycloud.domain.agent.internal;
