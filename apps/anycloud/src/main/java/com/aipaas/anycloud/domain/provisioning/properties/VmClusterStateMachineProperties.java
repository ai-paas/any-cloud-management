package com.aipaas.anycloud.domain.provisioning.properties;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * VmCluster state machine 운영 옵션.
 *
 * <p>Config prefix: {@code anycloud.vm-cluster.state-machine.*}
 *
 * <ul>
 *   <li>{@code strict=true} (default —) — invalid transition 시 즉시
 *       {@link IllegalStateException} throw. state machine 위반을 silent log 가 아닌 fail-fast
 *       로 감지. 운영 안정성 우선.</li>
 *   <li>{@code strict=false} — observation mode. invalid transition 시 log.warn 만, transition
 *       자체는 적용. legacy / 회귀 의심 시 임시 toggle.</li>
 * </ul>
 *
 * <p>적용 위치: {@link com.aipaas.anycloud.domain.provisioning.VmClusterEntity#transitionTo}.
 */
@Configuration
@ConfigurationProperties(prefix = "anycloud.vm-cluster.state-machine")
@Getter
@Setter
public class VmClusterStateMachineProperties {

    /** {@code true} (default) — invalid transition 시 throw. {@code false} — observation mode (legacy). */
    private boolean strict = true;
}
