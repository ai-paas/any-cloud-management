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
 * CSP 지원에는 서로를 참조하지 않는 세 지점을 모두 구현해야 함.
 *
 * <p>하나만 누락돼도 화면에는 노출. 사용자는 자격증명 등록까지 완료한 뒤 프로비저닝 단계에서
 * 실패를 확인. 누락 계층에 따라 실패 시점과 비용이 상이.
 *
 * <pre>
 * emitter 누락            → PROVISION 시작 시점에 실패
 * bootstrap 전략 누락      → 인프라 생성 완료 후 실패. VM 이 과금 상태로 잔존 (IBM)
 * VmOptionsProvider 누락  → 옵션 조회와 자격증명 상태 확인 실패 (Proxmox)
 * </pre>
 *
 * <p>세 사례 모두 실제 발생. enum 에 CSP 를 추가하는 시점에 누락 계층을 이 검사가 검출.
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
        // 인프라 생성 완료 후 전략 부재를 검출하면 지연. 생성된 VM 이 과금 상태로 잔존.
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
     * 클래스 이름으로 매칭. 빈 생성에는 생성자 의존성을 모두 채워야 하고, 그 경우 이 검사가
     * provider 추가마다 함께 수정해야 하는 목록이 됨.
     *
     * <p>이름 규칙 위반 시 이 지점에서 실패. 누락 미검출보다 안전.
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
