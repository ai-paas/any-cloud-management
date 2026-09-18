package com.aipaas.anycloud.domain.provisioning;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipaas.anycloud.domain.provisioning.bootstrap.VmClusterBootstrapStrategy;
import com.aipaas.anycloud.domain.provisioning.model.SupportedProvisioningProvider;
import com.aipaas.anycloud.domain.vmoptions.VmOptionsProvider;
import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import io.aipaas.cluster.provisioning.program.yaml.YamlEmitters;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;

/**
 * 한 CSP 를 지원하려면 서로를 모르는 세 곳을 모두 채워야 한다.
 *
 * <p>하나만 빠져도 화면에는 뜬다. 사용자는 자격증명까지 등록하고 프로비저닝에서야 실패를 본다.
 * 어느 층이 비었느냐에 따라 실패 시점과 비용이 다르다.
 *
 * <pre>
 * emitter 누락            → PROVISION 시작과 동시에 죽는다
 * bootstrap 전략 누락      → 인프라를 다 만든 뒤 죽는다. VM 이 과금되는 채로 남는다 (IBM)
 * VmOptionsProvider 누락  → 옵션 조회와 자격증명 상태 확인이 죽는다 (Proxmox)
 * </pre>
 *
 * <p>셋 다 실제로 겪은 일이다. enum 에 CSP 를 더하는 순간 빠진 층이 여기서 드러난다.
 */
class EveryProviderHasAllThreeLayersTest extends AbstractUnitTest {

    private static final String BASE_PACKAGE = "com.aipaas.anycloud.domain";

    @ParameterizedTest
    @EnumSource(SupportedProvisioningProvider.class)
    void hasAYamlEmitter(SupportedProvisioningProvider provider) {
        assertThat(YamlEmitters.supports(provider.getCanonicalName()))
                .as("%s 로 만든 요청이 PROVISION 시작과 동시에 죽는다", provider)
                .isTrue();
    }

    @ParameterizedTest
    @EnumSource(SupportedProvisioningProvider.class)
    void hasABootstrapStrategy(SupportedProvisioningProvider provider) {
        // 인프라를 다 만든 뒤에야 전략이 없다는 것을 알면 늦다. 만들어진 VM 이 과금되는 채로 남는다.
        assertThat(implementationNames(VmClusterBootstrapStrategy.class))
                .as("%s 가 BOOTSTRAP 단계에서 버려진다", provider)
                .anyMatch(name -> mentions(name, provider));
    }

    @ParameterizedTest
    @EnumSource(SupportedProvisioningProvider.class)
    void hasAVmOptionsProvider(SupportedProvisioningProvider provider) {
        /*
         * 없으면 "VM options provider is not registered" 로 끝난다. 프로비저닝뿐 아니라 자격증명
         * 상태 확인까지 막혀, 멀쩡한 자격증명이 "확인 불가" 로 보인다.
         */
        assertThat(implementationNames(VmOptionsProvider.class))
                .as("%s 의 옵션 조회와 자격증명 확인이 막힌다", provider)
                .anyMatch(name -> mentions(name, provider));
    }

    /**
     * 클래스 이름으로 짝을 짓는다. 빈을 만들려면 생성자 의존성을 모두 채워야 하는데, 그러면 이
     * 검사가 provider 하나 추가할 때마다 같이 손봐야 하는 또 다른 목록이 된다.
     *
     * <p>이름 규칙이 깨지면 여기서 실패한다 — 빠뜨린 것을 놓치는 쪽보다 낫다.
     */
    private static boolean mentions(String className, SupportedProvisioningProvider provider) {
        return className
                .toLowerCase(Locale.ROOT)
                .startsWith(provider.getCanonicalName().toLowerCase(Locale.ROOT));
    }

    private static List<String> implementationNames(Class<?> type) {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AssignableTypeFilter(type));
        Set<BeanDefinition> found = scanner.findCandidateComponents(BASE_PACKAGE);
        return found.stream()
                .map(BeanDefinition::getBeanClassName)
                .filter(java.util.Objects::nonNull)
                .map(name -> name.substring(name.lastIndexOf('.') + 1))
                .collect(Collectors.toList());
    }
}
