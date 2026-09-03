package com.aipaas.anycloud.domain.credential;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CspCredentialRepository extends JpaRepository<CspCredentialEntity, String> {

    List<CspCredentialEntity> findAllByOrderByCreatedAtDesc();
}
