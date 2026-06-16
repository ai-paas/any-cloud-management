package com.aipaas.anycloud.domain.helmrepo;

import com.aipaas.anycloud.domain.helmrepo.api.request.CreateHelmRepoRequest;
import com.aipaas.anycloud.domain.helmrepo.api.request.UpdateHelmRepoRequest;
import com.aipaas.anycloud.domain.helmrepo.model.HelmRepo;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpStatus;

/**
 * Helm chart repository CRUD.
 *
 * <p>SSOT 자동 sync 제거. helm_repo 는 단지 chart browsing /
 * INSTALL_ADDON 용 alias 등록부. ConfigMap 의 allowed_charts 와 무관.
 * 운영자가 chart 제한 원하면 PUT/PATCH /v1/admin/clusters/{c}/agent-policy 사용.
 *
 * <p>{@code *Entity} 메서드와 {@code *Domain*} 메서드가 양립합니다.
 * 새 caller 는 domain method 만 사용 (immutable record). entity method 는 점진 deprecate 됩니다.
 * 자세한 로드맵: {@code docs/architecture/design/domain-model-roadmap.md}.
 */
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
