package com.aipaas.anycloud.testsupport;

import org.springframework.test.context.ActiveProfiles;

/**
 * Controller slice 테스트의 공통 base. 구체 테스트 클래스에서 {@code @WebMvcTest(MyController.class)}
 * 와 함께 사용한다. 본 클래스는 profile 만 통일하고 spring context 는 직접 구성하지 않음.
 *
 * <pre>{@code
 * @WebMvcTest(MyController.class)
 * class MyControllerTest extends AbstractWebTest {
 *     @Autowired MockMvc mvc;
 *     @MockBean MyService svc;
 *     // ...
 * }
 * }</pre>
 */
@ActiveProfiles("test")
public abstract class AbstractWebTest {}
