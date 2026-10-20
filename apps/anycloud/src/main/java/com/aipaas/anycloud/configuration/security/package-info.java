/**
 * 백엔드 인증, 권한 컴포넌트 패키지.
 *
 * <p>{@link StaticTokenAuthFilter} 가 정적 토큰을 검증하고 {@link ImpersonationInterceptor} 가
 * gateway 가 첨부한 identity 헤더를 K8s impersonation 으로 전달. 양쪽 모두
 * {@link SecurityProperties} 의 토글로 제어.
 */
package com.aipaas.anycloud.configuration.security;
