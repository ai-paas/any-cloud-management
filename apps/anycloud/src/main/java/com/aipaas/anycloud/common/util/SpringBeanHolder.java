package com.aipaas.anycloud.common.util;

import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Component;

/**
 * JPA entity / 비-Spring-managed 객체가 Spring bean 에 접근하기 위한 static holder.
 *
 * <p><b>제한된 사용</b> — DI 가 핵심 (entity, util static method) 인 좁은 케이스만. 일반 Spring 빈은
 * 생성자 주입을 사용필요. 본 holder 는 다음 케이스용:
 * <ul>
 *   <li>JPA entity 의 method 가 service 호출 (예: {@code VmClusterEntity.transitionTo}
 *       내부에서 state history recorder 호출)</li>
 *   <li>@Configuration 외부의 static utility 안에서 bean 접근</li>
 * </ul>
 *
 * <p>Test 환경에서는 ApplicationContext 가 wire 안 된 상태일 수 있어 {@link #beanOrNull} 가 null
 * 반환 — 호출 측이 null-tolerant 필요.
 */
@Component
public class SpringBeanHolder implements ApplicationContextAware {

    private static ApplicationContext context;

    @Override
    public void setApplicationContext(ApplicationContext ctx) throws BeansException {
        context = ctx;
    }

    /** Bean lookup. context 가 wire 안 됐거나 bean 미존재 시 null 반환 (호출 측 null-check 필요). */
    public static <T> T beanOrNull(Class<T> type) {
        if (context == null) {
            return null;
        }
        try {
            return context.getBean(type);
        } catch (BeansException e) {
            return null;
        }
    }
}
