package com.aipaas.anycloud.domain.agent.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aipaas.anycloud.domain.agent.ClusterAgentEntity;
import com.aipaas.anycloud.domain.agent.ClusterAgentRepository;
import com.aipaas.anycloud.domain.agent.bootstrap.AgentBootstrapService.ClusterNotRegisteredException;
import com.aipaas.anycloud.domain.agent.bootstrap.AgentBootstrapService.RegistrationResult;
import com.aipaas.anycloud.domain.agent.model.ClusterAgentStatus;
import com.aipaas.anycloud.domain.cluster.ClusterEntity;
import com.aipaas.anycloud.domain.cluster.ClusterRepository;
import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import io.aipaas.cluster.agent.core.IdempotencyStore;
import io.aipaas.cluster.agent.identity.AgentJwtProperties;
import io.aipaas.cluster.agent.identity.AgentJwtProperties.Identity;
import io.aipaas.cluster.agent.identity.AgentJwtProperties.Jwt;
import io.aipaas.cluster.agent.identity.JwtRegistrationTokenService;
import io.aipaas.cluster.agent.identity.JwtRegistrationTokenService.IssuedToken;
import io.aipaas.cluster.agent.identity.JwtRegistrationTokenService.RegistrationTokenInvalidException;
import io.aipaas.cluster.agent.v1.AgentIdentity;
import io.aipaas.cluster.agent.v1.ClusterIdentity;
import io.aipaas.cluster.agent.v1.NetworkInfo;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.mockito.quality.Strictness;

/**
 * AgentBootstrapService 의 전체 register flow 회귀 보호.
 *
 * <p>실제 JWT 발급/검증을 거치고 (IdempotencyStore 만 mock), DB upsert 결과를 검증.
 */
class AgentBootstrapServiceTest extends AbstractUnitTest {

    private static final String SECRET = "test-secret-32-bytes-min-length-padding-padding-padding-padding";
    private static final String CLUSTER_ID = "demo-aws-01";

    private JwtRegistrationTokenService jwtService;
    private AgentJwtProperties properties;
    private ClusterRepository clusterRepository;
    private ClusterAgentRepository clusterAgentRepository;
    private AgentBootstrapService service;

    @BeforeEach
    void setUp() {
        IdempotencyStore idempotencyStore =
                Mockito.mock(IdempotencyStore.class, Mockito.withSettings().strictness(Strictness.LENIENT));
        when(idempotencyStore.tryLock(anyString(), any(Duration.class))).thenReturn(true);

        properties = new AgentJwtProperties(
                new Jwt(SECRET, "anycloud-bootstrap", "cluster-agent-registration", 600), new Identity(60));
        jwtService = new JwtRegistrationTokenService(properties, idempotencyStore, Clock.systemUTC());
        jwtService.initSigningKey();

        clusterRepository =
                Mockito.mock(ClusterRepository.class, Mockito.withSettings().strictness(Strictness.LENIENT));
        clusterAgentRepository = Mockito.mock(
                ClusterAgentRepository.class, Mockito.withSettings().strictness(Strictness.LENIENT));

        // 기본: cluster 존재함.
        ClusterEntity cluster = new ClusterEntity();
        cluster.setId(CLUSTER_ID);
        when(clusterRepository.findById(CLUSTER_ID)).thenReturn(Optional.of(cluster));

        // 기본: agent entity 새로 insert (existing 없음).
        when(clusterAgentRepository.findByClusterNameAndAgentInstanceId(anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(clusterAgentRepository.save(any(ClusterAgentEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        @SuppressWarnings("unchecked")
        org.springframework.beans.factory.ObjectProvider<com.aipaas.anycloud.domain.addon.AddonOrchestrator>
                emptyOrchProvider = org.mockito.Mockito.mock(
                        org.springframework.beans.factory.ObjectProvider.class,
                        org.mockito.Mockito.withSettings().strictness(Strictness.LENIENT));
        org.mockito.Mockito.when(emptyOrchProvider.getIfAvailable()).thenReturn(null);
        @SuppressWarnings("unchecked")
        org.springframework.beans.factory.ObjectProvider<com.aipaas.anycloud.domain.addon.AddonService>
                emptyAddonServiceProvider = org.mockito.Mockito.mock(
                        org.springframework.beans.factory.ObjectProvider.class,
                        org.mockito.Mockito.withSettings().strictness(Strictness.LENIENT));
        org.mockito.Mockito.when(emptyAddonServiceProvider.getIfAvailable()).thenReturn(null);

        service = new AgentBootstrapServiceImpl(
                jwtService,
                properties,
                clusterRepository,
                clusterAgentRepository,
                org.mockito.Mockito.mock(com.aipaas.anycloud.domain.agent.policy.ClusterPolicyBootstrapper.class),
                emptyOrchProvider,
                emptyAddonServiceProvider);
    }

    @Test
    void issueRegistrationToken_validCluster_returnsJwt() {
        IssuedToken issued = service.issueRegistrationToken(CLUSTER_ID, "MANUAL");

        assertThat(issued.token()).isNotBlank();
        assertThat(issued.ttlSeconds()).isEqualTo(600);
    }

    @Test
    void issueRegistrationToken_missingCluster_throws() {
        when(clusterRepository.findById("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.issueRegistrationToken("ghost", "MANUAL"))
                .isInstanceOf(ClusterNotRegisteredException.class);
    }

    @Test
    void register_validToken_savesEntityAndReturnsIdentityToken() {
        IssuedToken issued = service.issueRegistrationToken(CLUSTER_ID, "HELM_BOOTSTRAP");

        ClusterIdentity clusterIdentity = ClusterIdentity.newBuilder()
                .setK8SClusterUid("550e8400-e29b-41d4-a716-446655440000")
                .setVersion("1.34.3")
                .setDistribution("kubeadm")
                .setEndpoint("https://10.0.0.1:6443")
                .setPodCidr("192.168.0.0/16")
                .setServiceCidr("10.96.0.0/12")
                .build();
        AgentIdentity agentIdentity = AgentIdentity.newBuilder()
                .setAgentInstanceId("instance-1")
                .setVersion("1.0.0")
                .setPodName("aipaas-agent-abc")
                .build();
        NetworkInfo network = NetworkInfo.newBuilder()
                .setPublicIp("1.2.3.4")
                .setPrivateIp("10.0.0.1")
                .build();

        RegistrationResult result = service.register(issued.token(), clusterIdentity, agentIdentity, network);

        assertThat(result.clusterId()).isEqualTo(CLUSTER_ID);
        assertThat(result.agentIdentityToken()).isNotBlank();
        // Opaque token = 32 bytes hex = 64 chars.
        assertThat(result.agentIdentityToken()).hasSize(64);
        assertThat(result.status()).isEqualTo(ClusterAgentStatus.REGISTERED);
        assertThat(result.expiresAt()).isNotNull();

        // Entity 가 저장됨.
        ArgumentCaptor<ClusterAgentEntity> captor = ArgumentCaptor.forClass(ClusterAgentEntity.class);
        verify(clusterAgentRepository, times(1)).save(captor.capture());
        ClusterAgentEntity saved = captor.getValue();
        assertThat(saved.getClusterName()).isEqualTo(CLUSTER_ID);
        assertThat(saved.getAgentInstanceId()).isEqualTo("instance-1");
        assertThat(saved.getK8sClusterUid()).isEqualTo("550e8400-e29b-41d4-a716-446655440000");
        assertThat(saved.getK8sVersion()).isEqualTo("1.34.3");
        assertThat(saved.getDistribution()).isEqualTo("kubeadm");
        assertThat(saved.getStatus()).isEqualTo(ClusterAgentStatus.REGISTERED);
        // Identity token 자체는 entity 에 hash 만 — 원본 X.
        assertThat(saved.getIdentityTokenHash()).hasSize(64);
        assertThat(saved.getIdentityTokenHash())
                .isEqualTo(AgentBootstrapService.sha256Hex(result.agentIdentityToken()));
        assertThat(saved.getIdentityTokenHash()).isNotEqualTo(result.agentIdentityToken());
    }

    @Test
    void register_tamperedToken_throwsAndDoesNotSave() {
        IssuedToken issued = service.issueRegistrationToken(CLUSTER_ID, "MANUAL");
        String tampered = issued.token().substring(0, issued.token().length() - 5) + "XXXXX";

        assertThatThrownBy(() -> service.register(
                        tampered,
                        ClusterIdentity.getDefaultInstance(),
                        AgentIdentity.newBuilder().setAgentInstanceId("i").build(),
                        NetworkInfo.getDefaultInstance()))
                .isInstanceOf(RegistrationTokenInvalidException.class);

        verify(clusterAgentRepository, never()).save(any());
    }

    @Test
    void register_clusterDeletedAfterTokenIssued_throws() {
        IssuedToken issued = service.issueRegistrationToken(CLUSTER_ID, "MANUAL");
        // 발급 후 cluster 가 삭제됨.
        when(clusterRepository.findById(CLUSTER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.register(
                        issued.token(),
                        ClusterIdentity.getDefaultInstance(),
                        AgentIdentity.newBuilder().setAgentInstanceId("i").build(),
                        NetworkInfo.getDefaultInstance()))
                .isInstanceOf(ClusterNotRegisteredException.class);
    }

    @Test
    void register_existingInstance_updatesNotInserts() {
        IssuedToken issued = service.issueRegistrationToken(CLUSTER_ID, "MANUAL");

        // Agent 재시작 시 같은 instance_id 가 들어옴 — entity 가 이미 존재.
        ClusterAgentEntity existing = ClusterAgentEntity.builder()
                .agentId("agent-uuid-1")
                .clusterName(CLUSTER_ID)
                .agentInstanceId("instance-1")
                .identityTokenHash("old-hash")
                .status(ClusterAgentStatus.FAILED)
                .build();
        when(clusterAgentRepository.findByClusterNameAndAgentInstanceId(CLUSTER_ID, "instance-1"))
                .thenReturn(Optional.of(existing));

        RegistrationResult result = service.register(
                issued.token(),
                ClusterIdentity.newBuilder().setVersion("1.34.3").build(),
                AgentIdentity.newBuilder().setAgentInstanceId("instance-1").build(),
                NetworkInfo.getDefaultInstance());

        ArgumentCaptor<ClusterAgentEntity> captor = ArgumentCaptor.forClass(ClusterAgentEntity.class);
        verify(clusterAgentRepository).save(captor.capture());
        // 같은 agent_id 유지 (update, not insert).
        assertThat(captor.getValue().getAgentId()).isEqualTo("agent-uuid-1");
        // FAILED → REGISTERED 로 전환.
        assertThat(captor.getValue().getStatus()).isEqualTo(ClusterAgentStatus.REGISTERED);
        // 새 token hash 로 갱신.
        assertThat(captor.getValue().getIdentityTokenHash())
                .isEqualTo(AgentBootstrapService.sha256Hex(result.agentIdentityToken()));
    }

    @Test
    void register_agentInstanceIdMissing_generatesUuid() {
        IssuedToken issued = service.issueRegistrationToken(CLUSTER_ID, "MANUAL");

        service.register(
                issued.token(),
                ClusterIdentity.getDefaultInstance(),
                AgentIdentity.getDefaultInstance(), // instance_id 비어있음.
                NetworkInfo.getDefaultInstance());

        ArgumentCaptor<ClusterAgentEntity> captor = ArgumentCaptor.forClass(ClusterAgentEntity.class);
        verify(clusterAgentRepository).save(captor.capture());
        // UUID 형식 (36 chars).
        assertThat(captor.getValue().getAgentInstanceId()).hasSize(36);
    }
}
