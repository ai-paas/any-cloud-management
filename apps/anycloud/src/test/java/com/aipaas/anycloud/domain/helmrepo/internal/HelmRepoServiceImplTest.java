package com.aipaas.anycloud.domain.helmrepo.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aipaas.anycloud.common.error.exception.CustomException;
import com.aipaas.anycloud.common.error.exception.EntityNotFoundException;
import com.aipaas.anycloud.domain.helmrepo.HelmRepoEntity;
import com.aipaas.anycloud.domain.helmrepo.HelmRepoRepository;
import com.aipaas.anycloud.domain.helmrepo.api.request.CreateHelmRepoRequest;
import com.aipaas.anycloud.domain.helmrepo.api.request.UpdateHelmRepoRequest;
import com.aipaas.anycloud.domain.helmrepo.model.HelmRepoChangedEvent;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;

/**
 * {@link HelmRepoServiceImpl} broadcast event publish 회귀 lock.
 *
 * <p>CRUD path 각각 commit 후 정확히 1개 {@link HelmRepoChangedEvent} 발행 — async listener 가
 * cluster broadcast 트리거. event publish 가 saveAndFlush 이후 호출되는지 확인 (commit-전 race 방지).
 */
class HelmRepoServiceImplTest {

    private HelmRepoRepository repository;
    private ApplicationEventPublisher eventPublisher;
    private HelmRepoServiceImpl service;

    @BeforeEach
    void setUp() {
        repository = Mockito.mock(HelmRepoRepository.class);
        eventPublisher = Mockito.mock(ApplicationEventPublisher.class);
        service = new HelmRepoServiceImpl(
                repository,
                eventPublisher,
                org.mapstruct.factory.Mappers.getMapper(
                        com.aipaas.anycloud.domain.helmrepo.mapper.HelmRepoMapper.class));
    }

    // ============================================================================
    // getHelmRepoEntities / getHelmRepoEntity
    // ============================================================================

    @Test
    void getHelmRepoEntities_delegatesToRepository() {
        HelmRepoEntity a = HelmRepoEntity.builder().name("anycloud-internal").build();
        HelmRepoEntity b = HelmRepoEntity.builder().name("chartmuseum-external").build();
        when(repository.findAll()).thenReturn(List.of(a, b));

        List<HelmRepoEntity> result = service.getHelmRepoEntities();

        assertThat(result).hasSize(2);
        verify(repository).findAll();
    }

    @Test
    void getHelmRepoEntity_existing_returnsEntity() {
        HelmRepoEntity entity =
                HelmRepoEntity.builder().name("anycloud-internal").build();
        when(repository.findByName("anycloud-internal")).thenReturn(Optional.of(entity));

        HelmRepoEntity result = service.getHelmRepoEntity("anycloud-internal");

        assertThat(result.getName()).isEqualTo("anycloud-internal");
    }

    @Test
    void getHelmRepoEntity_missing_throwsEntityNotFound() {
        when(repository.findByName("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getHelmRepoEntity("missing"))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessageContaining("missing");
    }

    // ============================================================================
    // createHelmRepo
    // ============================================================================

    @Test
    void createHelmRepo_happyPath_savesAndPublishesCrudEvent() {
        CreateHelmRepoRequest req = createReq("new-repo", "https://example.com/charts");
        when(repository.existsByName("new-repo")).thenReturn(false);

        HttpStatus status = service.createHelmRepo(req);

        assertThat(status).isEqualTo(HttpStatus.CREATED);
        verify(repository, times(1)).save(any(HelmRepoEntity.class));
        verify(repository, times(1)).flush();

        // 회귀 lock — event 정확히 1개 발행 + operation="crud".
        ArgumentCaptor<HelmRepoChangedEvent> eventCaptor = ArgumentCaptor.forClass(HelmRepoChangedEvent.class);
        verify(eventPublisher, times(1)).publishEvent(eventCaptor.capture());
        HelmRepoChangedEvent event = eventCaptor.getValue();
        assertThat(event.repoName()).isEqualTo("new-repo");
        assertThat(event.operation()).isEqualTo("crud");
    }

    @Test
    void createHelmRepo_nullRequest_throws_noSaveNoEvent() {
        assertThatThrownBy(() -> service.createHelmRepo(null))
                .isInstanceOf(CustomException.class)
                .hasMessageContaining("HelmRepo is null");

        verify(repository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void createHelmRepo_duplicateName_throws_noEvent() {
        CreateHelmRepoRequest req = createReq("dup", "https://x.example.com");
        when(repository.existsByName("dup")).thenReturn(true);

        assertThatThrownBy(() -> service.createHelmRepo(req))
                .isInstanceOf(CustomException.class)
                .hasMessageContaining("Already Exists");

        verify(repository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void createHelmRepo_dataIntegrityViolation_throwsAndNoEvent() {
        CreateHelmRepoRequest req = createReq("repo", "https://x.example.com");
        when(repository.existsByName("repo")).thenReturn(false);
        Mockito.doThrow(new DataIntegrityViolationException("FK fail"))
                .when(repository)
                .save(any());

        assertThatThrownBy(() -> service.createHelmRepo(req)).isInstanceOf(CustomException.class);

        // save 시도는 했지만 DataIntegrity → event 발행 안 됨.
        verify(eventPublisher, never()).publishEvent(any());
    }

    // ============================================================================
    // deleteHelmRepo
    // ============================================================================

    @Test
    void deleteHelmRepo_existing_deletesAndPublishesDeleteEvent() {
        HelmRepoEntity existing = HelmRepoEntity.builder().name("to-delete").build();
        when(repository.findByName("to-delete")).thenReturn(Optional.of(existing));

        HttpStatus status = service.deleteHelmRepo("to-delete");

        assertThat(status).isEqualTo(HttpStatus.OK);
        verify(repository).delete(existing);

        ArgumentCaptor<HelmRepoChangedEvent> eventCaptor = ArgumentCaptor.forClass(HelmRepoChangedEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().repoName()).isEqualTo("to-delete");
        assertThat(eventCaptor.getValue().operation())
                .as("H-50 orphan cleanup trigger — operation=delete 가 핵심")
                .isEqualTo("delete");
    }

    @Test
    void deleteHelmRepo_missing_throws_noEvent() {
        when(repository.findByName("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteHelmRepo("missing"))
                .isInstanceOf(CustomException.class)
                .hasMessageContaining("Not Found");

        verify(repository, never()).delete(any(HelmRepoEntity.class));
        verify(eventPublisher, never()).publishEvent(any());
    }

    // ============================================================================
    // updateHelmRepo — partial update + broadcast
    // ============================================================================

    @Test
    void updateHelmRepo_partialUpdate_skipsNullFields() {
        HelmRepoEntity existing = HelmRepoEntity.builder()
                .name("repo")
                .url("https://old.example.com")
                .username("olduser")
                .password("oldpass")
                .build();
        when(repository.findByName("repo")).thenReturn(Optional.of(existing));

        UpdateHelmRepoRequest update = new UpdateHelmRepoRequest();
        update.setUrl("https://new.example.com");
        // username/password 는 null → 변경 안 됨.

        service.updateHelmRepo("repo", update);

        assertThat(existing.getUrl()).isEqualTo("https://new.example.com");
        assertThat(existing.getUsername()).as("null 입력 → 기존 값 유지").isEqualTo("olduser");
        assertThat(existing.getPassword()).isEqualTo("oldpass");
    }

    @Test
    void updateHelmRepo_publishesUpdateEvent() {
        HelmRepoEntity existing = HelmRepoEntity.builder().name("repo").build();
        when(repository.findByName("repo")).thenReturn(Optional.of(existing));

        UpdateHelmRepoRequest update = new UpdateHelmRepoRequest();
        update.setUrl("https://new.example.com");

        service.updateHelmRepo("repo", update);

        ArgumentCaptor<HelmRepoChangedEvent> eventCaptor = ArgumentCaptor.forClass(HelmRepoChangedEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().operation()).isEqualTo("update");
    }

    @Test
    void updateHelmRepo_nullRequest_throws_noEvent() {
        assertThatThrownBy(() -> service.updateHelmRepo("repo", null))
                .isInstanceOf(CustomException.class)
                .hasMessageContaining("Update is null");

        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void updateHelmRepo_missing_throws_noEvent() {
        when(repository.findByName("missing")).thenReturn(Optional.empty());

        UpdateHelmRepoRequest update = new UpdateHelmRepoRequest();
        update.setUrl("x");

        assertThatThrownBy(() -> service.updateHelmRepo("missing", update))
                .isInstanceOf(CustomException.class)
                .hasMessageContaining("Not Found");

        verify(repository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    // ============================================================================
    // helper
    // ============================================================================

    private static CreateHelmRepoRequest createReq(String name, String url) {
        CreateHelmRepoRequest req = new CreateHelmRepoRequest();
        req.setName(name);
        req.setUrl(url);
        return req;
    }
}
