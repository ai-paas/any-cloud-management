package com.aipaas.anycloud.domain.agent.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aipaas.anycloud.domain.addon.AddonOrchestrator;
import com.aipaas.anycloud.domain.agent.ClusterAgentEntity;
import com.aipaas.anycloud.domain.agent.ClusterAgentRepository;
import com.aipaas.anycloud.domain.agent.bootstrap.AgentBootstrapService.ClusterNotRegisteredException;
import com.aipaas.anycloud.domain.agent.bootstrap.AgentBootstrapService.RotationDeniedException;
import com.aipaas.anycloud.domain.agent.bootstrap.AgentBootstrapService.RotationResult;
import com.aipaas.anycloud.domain.agent.policy.ClusterPolicyBootstrapper;
import com.aipaas.anycloud.domain.cluster.ClusterRepository;
import io.aipaas.cluster.agent.identity.AgentJwtProperties;
import io.aipaas.cluster.agent.identity.JwtRegistrationTokenService;
import io.aipaas.cluster.agent.identity.JwtRegistrationTokenService.IssuedToken;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;

/**
 * {@link AgentBootstrapServiceImpl} 보안 critical path 회귀 lock —.
 *
 * <p>cluster agent identity token 발급/rotation 의 거부 분기 회귀 lock. 회귀 시 revoked / expired
 * agent 가 rotation 으로 영구 token 재발급 → security incident.
 *
 * <p>register() flow 는 proto-heavy 라 본 테스트에서 제외 — 별 integration test 영역.
 */
class AgentBootstrapServiceImplTest {

    private JwtRegistrationTokenService jwtService;
    private AgentJwtProperties properties;
    private ClusterRepository clusterRepository;
    private ClusterAgentRepository clusterAgentRepository;
    private ClusterPolicyBootstrapper clusterPolicyBootstrapper;
    private ObjectProvider<AddonOrchestrator> addonOrchestratorProvider;
    private AgentBootstrapServiceImpl service;

    @BeforeEach
    void setUp() {
        jwtService = Mockito.mock(JwtRegistrationTokenService.class);
        properties = new AgentJwtProperties(
                new AgentJwtProperties.Jwt(
                        "test-secret-32chars-padding-padding-padding", "anycloud-test", "cluster-agent", 600L),
                new AgentJwtProperties.Identity(60));
        clusterRepository = Mockito.mock(ClusterRepository.class);
        clusterAgentRepository = Mockito.mock(ClusterAgentRepository.class);
        clusterPolicyBootstrapper = Mockito.mock(ClusterPolicyBootstrapper.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<AddonOrchestrator> provider = Mockito.mock(ObjectProvider.class);
        this.addonOrchestratorProvider = provider;
        @SuppressWarnings("unchecked")
        ObjectProvider<com.aipaas.anycloud.domain.addon.AddonService> addonServiceProvider =
                Mockito.mock(ObjectProvider.class);

        service = new AgentBootstrapServiceImpl(
                jwtService,
                properties,
                clusterRepository,
                clusterAgentRepository,
                clusterPolicyBootstrapper,
                addonOrchestratorProvider,
                addonServiceProvider);
    }

    // ============================================================================
    // issueRegistrationToken
    // ============================================================================

    @Test
    void issueRegistrationToken_clusterMissing_throws() {
        when(clusterRepository.findById("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.issueRegistrationToken("missing", "MANUAL"))
                .isInstanceOf(ClusterNotRegisteredException.class)
                .hasMessageContaining("missing");

        verify(jwtService, never()).issue(anyString(), anyString());
    }

    @Test
    void issueRegistrationToken_nullInstallMode_defaultsToManual() {
        when(clusterRepository.findById("c1"))
                .thenReturn(Optional.of(new com.aipaas.anycloud.domain.cluster.ClusterEntity()));
        IssuedToken token = new IssuedToken("jwt-xxx", "jti-123", Instant.now().plusSeconds(600), 600L);
        when(jwtService.issue(eq("c1"), eq("MANUAL"))).thenReturn(token);

        IssuedToken result = service.issueRegistrationToken("c1", null);

        assertThat(result.token()).isEqualTo("jwt-xxx");
        verify(jwtService).issue("c1", "MANUAL");
    }

    @Test
    void issueRegistrationToken_blankInstallMode_defaultsToManual() {
        when(clusterRepository.findById("c1"))
                .thenReturn(Optional.of(new com.aipaas.anycloud.domain.cluster.ClusterEntity()));
        when(jwtService.issue(any(), eq("MANUAL")))
                .thenReturn(new IssuedToken("t", "j", Instant.now().plusSeconds(600), 600L));

        service.issueRegistrationToken("c1", "  ");

        verify(jwtService).issue("c1", "MANUAL");
    }

    @Test
    void issueRegistrationToken_lowerCaseMode_normalizedToUpperCase() {
        when(clusterRepository.findById("c1"))
                .thenReturn(Optional.of(new com.aipaas.anycloud.domain.cluster.ClusterEntity()));
        when(jwtService.issue(any(), anyString()))
                .thenReturn(new IssuedToken("t", "j", Instant.now().plusSeconds(600), 600L));

        service.issueRegistrationToken("c1", "helm_bootstrap");

        verify(jwtService).issue("c1", "HELM_BOOTSTRAP");
    }

    // ============================================================================
    // rotateIdentityToken
    // ============================================================================

    @Test
    void rotateIdentityToken_tokenNotFound_throws() {
        when(clusterAgentRepository.findByIdentityTokenHash("hash-missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.rotateIdentityToken("hash-missing", "instance-1"))
                .isInstanceOf(RotationDeniedException.class)
                .hasMessageContaining("not found");

        verify(clusterAgentRepository, never()).save(any());
    }

    @Test
    void rotateIdentityToken_revoked_throws() {
        ClusterAgentEntity revoked = ClusterAgentEntity.builder()
                .agentId("a1")
                .clusterName("c1")
                .agentInstanceId("i1")
                .identityTokenHash("hash-rev")
                .revokedAt(LocalDateTime.of(2026, 6, 1, 12, 0))
                .expiresAt(LocalDateTime.now().plusDays(30))
                .build();
        when(clusterAgentRepository.findByIdentityTokenHash("hash-rev")).thenReturn(Optional.of(revoked));

        assertThatThrownBy(() -> service.rotateIdentityToken("hash-rev", "instance-1"))
                .isInstanceOf(RotationDeniedException.class)
                .hasMessageContaining("revoked");

        // 회귀 lock — revoked agent 가 rotation 으로 부활하면 보안 사고.
        verify(clusterAgentRepository, never()).save(any());
    }

    @Test
    void rotateIdentityToken_expired_throws() {
        ClusterAgentEntity expired = ClusterAgentEntity.builder()
                .agentId("a1")
                .clusterName("c1")
                .agentInstanceId("i1")
                .identityTokenHash("hash-exp")
                .revokedAt(null)
                .expiresAt(LocalDateTime.now().minusDays(1)) // 어제 만료
                .build();
        when(clusterAgentRepository.findByIdentityTokenHash("hash-exp")).thenReturn(Optional.of(expired));

        assertThatThrownBy(() -> service.rotateIdentityToken("hash-exp", "instance-1"))
                .isInstanceOf(RotationDeniedException.class)
                .hasMessageContaining("expired");

        // 회귀 lock — 만료된 token 으로 rotation 가능하면 영구 갱신 가능 → security 위반.
        verify(clusterAgentRepository, never()).save(any());
    }

    @Test
    void rotateIdentityToken_happyPath_savesNewHashAndExtendsExpiry() {
        ClusterAgentEntity living = ClusterAgentEntity.builder()
                .agentId("a1")
                .clusterName("c1")
                .agentInstanceId("i1")
                .identityTokenHash("hash-old")
                .revokedAt(null)
                .expiresAt(LocalDateTime.now().plusDays(10))
                .build();
        when(clusterAgentRepository.findByIdentityTokenHash("hash-old")).thenReturn(Optional.of(living));

        RotationResult result = service.rotateIdentityToken("hash-old", "instance-1");

        // 새 token 반환됨.
        assertThat(result.newToken()).isNotBlank();
        assertThat(result.newToken().length())
                .as("256-bit opaque token → 64 hex chars")
                .isEqualTo(64);
        // 만료 시점이 60일 (ttlDays) 후로 갱신 됨.
        assertThat(result.expiresAt())
                .isAfter(LocalDateTime.now().plusDays(59))
                .isBefore(LocalDateTime.now().plusDays(61));

        // save 호출 + entity 의 hash/expires 가 갱신됨.
        ArgumentCaptor<ClusterAgentEntity> entityCaptor = ArgumentCaptor.forClass(ClusterAgentEntity.class);
        verify(clusterAgentRepository).save(entityCaptor.capture());
        ClusterAgentEntity saved = entityCaptor.getValue();
        assertThat(saved.getIdentityTokenHash())
                .as("hash 가 갱신되어 이전 token 으로는 더 이상 인증 불가")
                .isNotEqualTo("hash-old");
        assertThat(saved.getRevokedAt())
                .as("happy rotation 후 revokedAt 명시적으로 null")
                .isNull();
    }

    @Test
    void rotateIdentityToken_secondCall_generatesDifferentToken() {
        // 256-bit opaque token randomness 회귀 — 두 번 rotate 시 같은 token 절대 안 됨.
        ClusterAgentEntity living = ClusterAgentEntity.builder()
                .agentId("a1")
                .clusterName("c1")
                .identityTokenHash("hash-old")
                .expiresAt(LocalDateTime.now().plusDays(10))
                .build();
        when(clusterAgentRepository.findByIdentityTokenHash("hash-old")).thenReturn(Optional.of(living));

        String token1 = service.rotateIdentityToken("hash-old", "i1").newToken();
        String token2 = service.rotateIdentityToken("hash-old", "i1").newToken();

        assertThat(token1).as("SecureRandom 기반 — 같은 input 두 번 → 다른 token").isNotEqualTo(token2);
    }
}
