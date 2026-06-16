package com.aipaas.anycloud.domain.helmrepo.internal;

import com.aipaas.anycloud.common.error.enums.ErrorCode;
import com.aipaas.anycloud.common.error.exception.CustomException;
import com.aipaas.anycloud.common.error.exception.EntityNotFoundException;
import com.aipaas.anycloud.domain.helmrepo.HelmRepoEntity;
import com.aipaas.anycloud.domain.helmrepo.HelmRepoRepository;
import com.aipaas.anycloud.domain.helmrepo.HelmRepoService;
import com.aipaas.anycloud.domain.helmrepo.api.request.CreateHelmRepoRequest;
import com.aipaas.anycloud.domain.helmrepo.api.request.UpdateHelmRepoRequest;
import com.aipaas.anycloud.domain.helmrepo.mapper.HelmRepoMapper;
import com.aipaas.anycloud.domain.helmrepo.model.HelmRepo;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Helm repo CRUD impl.
 *
 * <p>SSOT 자동 sync 제거. helm_repo CRUD 는 단지 DB 의 chart browsing
 * 메타데이터. ConfigMap 의 allowed_charts 와 무관. 운영자가 chart 제한을 원하면 별도로
 * PUT /v1/admin/clusters/{c}/agent-policy 호출. ApplicationEventPublisher 의존 제거.
 */
@Slf4j
@Service
// class-level default = readOnly. write 메서드는 명시 override.
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class HelmRepoServiceImpl implements HelmRepoService {

    private final HelmRepoRepository helmRepoRepository;
    // CRUD 후 broadcast event publish — Spring TransactionalEventListener 가 commit 후 dispatch
    // (caller transaction commit 전 dispatch 로 인한 미커밋 row 조회 race 방지).
    private final org.springframework.context.ApplicationEventPublisher eventPublisher;
    private final HelmRepoMapper helmRepoMapper;

    @Override
    @Transactional(readOnly = true)
    public List<HelmRepoEntity> getHelmRepoEntities() {
        return helmRepoRepository.findAll();
    }

    @Override
    @Transactional(readOnly = true)
    public HelmRepoEntity getHelmRepoEntity(String helmRepoName) {
        return helmRepoRepository
                .findByName(helmRepoName)
                .orElseThrow(() -> new EntityNotFoundException("HelmRepo with Name " + helmRepoName + " Not Found."));
    }

    // ===== domain method =====

    @Override
    @Transactional(readOnly = true)
    public List<HelmRepo> findAllDomain() {
        return helmRepoRepository.findAll().stream()
                .map(helmRepoMapper::toDomain)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<HelmRepo> findDomainByName(String name) {
        return helmRepoRepository.findByName(name).map(helmRepoMapper::toDomain);
    }

    @Override
    @Transactional
    public HttpStatus createHelmRepo(CreateHelmRepoRequest helmRepo) {
        if (helmRepo == null) {
            throw new CustomException(ErrorCode.INVALID_INPUT_VALUE, "helmRepo", "null", "HelmRepo is null.");
        }

        if (helmRepoRepository.existsByName(helmRepo.getName())) {
            throw new CustomException(
                    ErrorCode.DUPLICATE,
                    "name",
                    helmRepo.getName(),
                    "HelmRepo with Name " + helmRepo.getName() + " Already Exists.");
        }

        HelmRepoEntity.HelmRepoEntityBuilder builder = HelmRepoEntity.builder()
                .name(helmRepo.getName())
                .url(helmRepo.getUrl())
                .username(helmRepo.getUsername())
                .password(helmRepo.getPassword())
                .caFile(helmRepo.getCaFile())
                .insecureSkipTlsVerify(helmRepo.isInsecureSkipTlsVerify())
                .tags(helmRepo.getTags());
        // source default = EXTERNAL (entity default 와 동일) — null 일 때만 entity default 적용.
        if (helmRepo.getSource() != null) {
            builder.source(helmRepo.getSource());
        }
        HelmRepoEntity helmRepoEntity = builder.build();

        try {
            helmRepoRepository.save(helmRepoEntity);
            helmRepoRepository.flush();
        } catch (DataIntegrityViolationException e) {
            log.error("Failed to create helm repository {}", helmRepo.getName(), e);
            throw new CustomException(ErrorCode.DATA_INTEGRITY);
        }
        // 모든 ACTIVE cluster 에 fresh helm_repositories broadcast.
        eventPublisher.publishEvent(new com.aipaas.anycloud.domain.helmrepo.model.HelmRepoChangedEvent(
                helmRepoEntity != null ? helmRepoEntity.getName() : "<n/a>", "crud"));
        return HttpStatus.CREATED;
    }

    @Override
    @Transactional
    public HttpStatus deleteHelmRepo(String helmRepoName) {
        HelmRepoEntity existing = helmRepoRepository
                .findByName(helmRepoName)
                .orElseThrow(() -> new CustomException(
                        ErrorCode.ENTITY_NOT_FOUND,
                        "name",
                        helmRepoName,
                        "HelmRepo with Name " + helmRepoName + " Not Found."));
        helmRepoRepository.delete(existing);
        // delete 도 broadcast — agent 측 RepositoryFile 의 orphan 정리는 agent reconciler 가 담당.
        eventPublisher.publishEvent(
                new com.aipaas.anycloud.domain.helmrepo.model.HelmRepoChangedEvent(helmRepoName, "delete"));
        return HttpStatus.OK;
    }

    @Override
    @Transactional
    public HttpStatus updateHelmRepo(String helmRepoName, UpdateHelmRepoRequest update) {
        if (update == null) {
            throw new CustomException(ErrorCode.INVALID_INPUT_VALUE, "update", "null", "Update is null.");
        }
        HelmRepoEntity entity = helmRepoRepository
                .findByName(helmRepoName)
                .orElseThrow(() -> new CustomException(
                        ErrorCode.ENTITY_NOT_FOUND,
                        "name",
                        helmRepoName,
                        "HelmRepo with Name " + helmRepoName + " Not Found."));

        // null 필드 skip — partial update.
        if (update.getUrl() != null) entity.setUrl(update.getUrl());
        if (update.getUsername() != null) entity.setUsername(update.getUsername());
        if (update.getPassword() != null) entity.setPassword(update.getPassword());
        if (update.getCaFile() != null) entity.setCaFile(update.getCaFile());
        if (update.getInsecureSkipTlsVerify() != null) {
            entity.setInsecureSkipTlsVerify(update.getInsecureSkipTlsVerify());
        }
        // hybrid helm-repo metadata.
        if (update.getSource() != null) entity.setSource(update.getSource());
        if (update.getTags() != null) entity.setTags(update.getTags());
        helmRepoRepository.save(entity);
        // 변경된 metadata 도 broadcast — url 변경이 가장 흔한 케이스.
        eventPublisher.publishEvent(
                new com.aipaas.anycloud.domain.helmrepo.model.HelmRepoChangedEvent(entity.getName(), "update"));
        return HttpStatus.OK;
    }
}
