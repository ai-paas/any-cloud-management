package com.aipaas.anycloud.domain.credential;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CspCredentialRepository extends JpaRepository<CspCredentialEntity, String> {

    List<CspCredentialEntity> findAllByOrderByCreatedAtDesc();

    /** provider 내 이름은 고유. DB 제약 위반 전에 사용자 확인 가능한 오류를 반환하기 위한 선행 조회. */
    boolean existsByProviderAndName(String provider, String name);
}
