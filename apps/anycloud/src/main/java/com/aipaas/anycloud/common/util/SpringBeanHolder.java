package com.aipaas.anycloud.common.util;

import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Component;

/**
 * JPA entity 처럼 Spring 이 관리하지 않는 객체의 bean 조회용 static holder.
 * 생성자 주입이 불가한 지점에서만 사용. 일반 bean 은 생성자 주입 사용.
 *
 * <p>테스트에서는 ApplicationContext 미초기화 상태가 있어 {@link #beanOrNull} 이 null 반환.
 * 호출 측의 null 처리 필요.
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
