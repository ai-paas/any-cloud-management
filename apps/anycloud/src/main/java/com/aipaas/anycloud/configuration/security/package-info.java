/**
 * 백엔드 인증과 권한 컴포넌트를 모아 둔 패키지입니다.
 *
 * <p>{@link StaticTokenAuthFilter} 가 정적 토큰을 검증하고,
 * {@link ImpersonationInterceptor} 가 gateway 가 붙인 identity 헤더를 K8s impersonation 으로
 * 넘깁니다. {@link SecurityProperties} 의 토글이 둘 다를 켜고 끕니다.
 */
package com.aipaas.anycloud.configuration.security;
