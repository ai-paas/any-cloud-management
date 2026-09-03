package com.aipaas.anycloud.domain.agent;

import java.time.LocalDateTime;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BootstrapJtiRepository extends JpaRepository<BootstrapJtiEntity, String> {

    /** 만료된 jti 행 삭제. cleanup sweeper 가 호출. */
    @Modifying
    @Query("DELETE FROM BootstrapJtiEntity j WHERE j.expiresAt < :cutoff")
    int deleteExpiredBefore(@Param("cutoff") LocalDateTime cutoff);
}
