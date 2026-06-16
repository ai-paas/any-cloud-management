/**
 * 백엔드 인증 / 권한 관련 컴포넌트의 단일 패키지.
 *
 * <p>3 핵심 컴포넌트:
 * <ul>
 *   <li>{@link StaticTokenAuthFilter} — 정적 토큰 검증 filter (gateway 뒤에서만 유효).</li>
 *   <li>{@link WebSecurityConfig} — Spring Security 설정. filter chain ordering.</li>
 *   <li>{@link ImpersonationInterceptor} — K8s impersonation pass-through 헤더 (X-User-* / X-Groups)
 *       조합. 사용자 단위 K8s RBAC enforcement 의 진입점.</li>
 * </ul>
 *
 * <p>{@link WebSecurityDisabledConfig} 는 dev / test 환경에서 SecurityConfig 를 무력화.
 * {@link SecurityProperties} 가 toggle 보유.
 */
package com.aipaas.anycloud.configuration.security;
