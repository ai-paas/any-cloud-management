package com.aipaas.anycloud.testsupport;

import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Spring context 가 필요 없는 단위 테스트의 공통 base. Mockito Extension 만 활성화.
 * <p>
 * 시작이 가장 빠르므로 가능한 한 unit 테스트로 작성하고, DB/AMQP 가 필요할 때만
 * {@link AbstractIntegrationTest} 로 격상한다.
 */
@ExtendWith(MockitoExtension.class)
public abstract class AbstractUnitTest {}
