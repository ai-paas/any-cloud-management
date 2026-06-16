package io.aipaas.cluster.agent.rbac.autoconfigure;

import io.aipaas.cluster.agent.rbac.internal.AgentBindingApplyClient;
import io.aipaas.cluster.agent.rbac.internal.ClasspathBindingTemplateCatalog;
import io.aipaas.cluster.agent.rbac.internal.LoggingBindingAuditSink;
import io.aipaas.cluster.agent.rbac.internal.SimpleBindingFleetView;
import io.aipaas.cluster.agent.rbac.port.BindingApplyClient;
import io.aipaas.cluster.agent.rbac.port.BindingAuditSink;
import io.aipaas.cluster.agent.rbac.port.BindingFleetView;
import io.aipaas.cluster.agent.rbac.port.BindingTemplateCatalog;
import io.aipaas.cluster.agent.runtime.KubeResourceService;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * RBAC starter auto-config.
 *
 * <p>활성 조건: Layer 1 의 {@link KubeResourceService} 가 Spring context 에 존재. 호스트 application
 * 이 cluster-agent-spring-boot-starter 의존성 추가 후 cluster registry SPI 만 채우면 본 모듈이
 * 자동 활성.
 *
 * <p>모든 bean 은 {@code @ConditionalOnMissingBean} — 호스트가 자체 impl 제공 시 그게 우선.
 */
@AutoConfiguration
@ConditionalOnClass(KubeResourceService.class)
@EnableConfigurationProperties(ClusterAgentRbacProperties.class)
public class ClusterAgentRbacAutoConfiguration {

	@Bean
	@ConditionalOnMissingBean
	public BindingTemplateCatalog bindingTemplateCatalog(ClusterAgentRbacProperties props) {
		return new ClasspathBindingTemplateCatalog(props.templates().classpathResource());
	}

	@Bean
	@ConditionalOnMissingBean
	public BindingAuditSink bindingAuditSink() {
		return new LoggingBindingAuditSink();
	}

	@Bean
	@ConditionalOnMissingBean
	@ConditionalOnBean(KubeResourceService.class)
	public BindingApplyClient bindingApplyClient(KubeResourceService kubeService,
			BindingAuditSink auditSink,
			ClusterAgentRbacProperties props) {
		return new AgentBindingApplyClient(kubeService, auditSink, props);
	}

	@Bean
	@ConditionalOnMissingBean
	@ConditionalOnBean(KubeResourceService.class)
	public BindingFleetView bindingFleetView(KubeResourceService kubeService,
			ClusterAgentRbacProperties props) {
		return new SimpleBindingFleetView(kubeService, props);
	}
}
