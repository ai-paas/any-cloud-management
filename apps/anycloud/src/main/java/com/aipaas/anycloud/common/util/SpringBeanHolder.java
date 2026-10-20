package com.aipaas.anycloud.common.util;

import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Component;

/**
 * JPA entity 처럼 Spring 이 관리하지 않는 객체가 bean 을 꺼내 쓰기 위한 static holder 입니다.
 * 생성자 주입이 닿지 않는 자리에서만 쓰세요. 일반 bean 은 생성자 주입을 씁니다.
 *
 * <p>테스트에서는 ApplicationContext 가 아직 없을 수 있어 {@link #beanOrNull} 이 null 을
 * 돌려줍니다. 호출 측이 null 을 견뎌야 합니다.
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
