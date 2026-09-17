package com.aipaas.anycloud.domain.helmrepo;

import com.aipaas.anycloud.domain.helmrepo.api.request.CreateHelmRepoRequest;
import com.aipaas.anycloud.domain.helmrepo.api.request.UpdateHelmRepoRequest;
import com.aipaas.anycloud.domain.helmrepo.model.HelmRepo;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpStatus;

/** Helm chart repository CRUD. */
public interface HelmRepoService {
    // ===== Entity 반환 (legacy) — 점진 deprecate =====
    List<HelmRepoEntity> getHelmRepoEntities();

    HelmRepoEntity getHelmRepoEntity(String name);

    // ===== Domain 반환 — 새 caller 는 이쪽 사용 =====

    /** 모든 helm repo 의 immutable 도메인 표현. {@link #getHelmRepoEntities()} 의 domain 변형. */
    List<HelmRepo> findAllDomain();

    /** name 으로 helm repo 의 immutable 도메인 표현 조회. {@link #getHelmRepoEntity(String)} 의 domain 변형. */
    Optional<HelmRepo> findDomainByName(String name);

    HttpStatus createHelmRepo(CreateHelmRepoRequest createHelmRepoDto);

    HttpStatus deleteHelmRepo(String name);

    /**
     * Helm repo partial update. null 필드는 현재 값 유지.
     *
     * <p>name 변경은 미지원 — URL identity. 변경 필요하면 delete + create.
     *
     * @return 200 OK on success
     */
    HttpStatus updateHelmRepo(String name, UpdateHelmRepoRequest updateHelmRepoDto);
}
