/**
 * 테스트 인프라(공통 base class, Testcontainers, helper).
 * <p>
 * 카테고리:
 * <ul>
 *   <li>{@link com.aipaas.anycloud.testsupport.AbstractUnitTest} — 순수 단위 테스트.
 *       Spring context 없음. Mockito + AssertJ.</li>
 *   <li>{@link com.aipaas.anycloud.testsupport.AbstractWebTest} — controller slice 테스트.
 *       {@code @WebMvcTest} 기반. MockMvc 직접 사용.</li>
 *   <li>{@link com.aipaas.anycloud.testsupport.AbstractIntegrationTest} — 전체 컨텍스트 +
 *       Testcontainers MariaDB. {@code @SpringBootTest} 기반.</li>
 * </ul>
 */
package com.aipaas.anycloud.testsupport;
