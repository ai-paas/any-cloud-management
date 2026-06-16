package com.aipaas.anycloud.domain.agent;

import java.time.LocalDateTime;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecordEntity, String> {

    @Modifying
    @Query("DELETE FROM IdempotencyRecordEntity r WHERE r.expiresAt < :now")
    int deleteExpired(@Param("now") LocalDateTime now);
}
