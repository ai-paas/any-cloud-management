package com.aipaas.anycloud.domain.credential;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CspCredentialRepository extends JpaRepository<CspCredentialEntity, String> {

    List<CspCredentialEntity> findAllByOrderByCreatedAtDesc();

    /** 같은 provider 안에서 이름은 유일하다. DB 제약이 터지기 전에 사람이 읽을 오류를 주려고 먼저 본다. */
    boolean existsByProviderAndName(String provider, String name);
}
