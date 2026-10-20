package com.aipaas.anycloud.domain.provisioning;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface VmClusterRepository
        extends JpaRepository<VmClusterEntity, String>, JpaSpecificationExecutor<VmClusterEntity> {

    /**
     * 처리 소유권 획득. 다른 메시지가 선점한 상태면 0 반환.
     *
     * <p>조건부 UPDATE 단일 쿼리로 처리. 읽고 쓰는 방식은 두 워커가 같은 틈에 진입 가능.
     * staleBefore 이전 소유권은 워커 종료로 간주해 회수.
     *
     * <p>조건에 {@code processingMessageId = :messageId} 를 두면 AMQP 재전달이 동일 messageId 로
     * 자기 락을 재획득해 같은 작업이 중복 실행. 크래시 후 재시도는 stale 창으로 처리하고,
     * messageId 일치만으로는 재진입 불허.
     */
    @Modifying
    @Query("update VmClusterEntity v set v.processingMessageId = :messageId, v.processingStartedAt = :now "
            + "where v.id = :id and (v.processingMessageId is null "
            + "or v.processingStartedAt < :staleBefore)")
    int acquireProcessing(
            @Param("id") String id,
            @Param("messageId") String messageId,
            @Param("now") LocalDateTime now,
            @Param("staleBefore") LocalDateTime staleBefore);

    /**
     * 소유자와 무관하게 소유권 탈취. 삭제 전용.
     *
     * <p>stale 창을 단계 예산에 맞추면 삭제가 그 뒤에 대기. 정지된 프로비저닝 삭제에 수 시간
     * 소요. 앞 단계 결과는 폐기 대상이므로 선점해도 손실 없음.
     */
    @Modifying
    @Query("update VmClusterEntity v set v.processingMessageId = :messageId, v.processingStartedAt = :now "
            + "where v.id = :id")
    int takeOverProcessing(
            @Param("id") String id, @Param("messageId") String messageId, @Param("now") LocalDateTime now);

    /** 자신이 획득한 소유권만 해제. 회수된 이후면 변경 없음. */
    @Modifying
    @Query("update VmClusterEntity v set v.processingMessageId = null, v.processingStartedAt = null "
            + "where v.id = :id and v.processingMessageId = :messageId")
    int releaseProcessing(@Param("id") String id, @Param("messageId") String messageId);

    /** 조정 루프 대상: READY / DEGRADED 클러스터. */
    List<VmClusterEntity> findByProvisioningStatusIn(
            java.util.Collection<com.aipaas.anycloud.domain.provisioning.model.VmClusterStatus> statuses);

    Optional<VmClusterEntity> findFirstByClusterNameOrderByCreatedAtDesc(String clusterName);

    /** 동일 이름의 전체 세대. 재시도로 누적된 이전 행까지 정리할 때 사용. */
    List<VmClusterEntity> findAllByClusterNameOrderByCreatedAtDesc(String clusterName);

    List<VmClusterEntity> findAllByOrderByCreatedAtDesc();

    boolean existsByActiveRequestKey(String activeRequestKey);

    long countByCredentialIdAndActiveRequestKeyIsNotNull(String credentialId);

    /**
     * 보관 기한이 지난 삭제 이력 제거.
     *
     * <p>삭제 시 감사와 원인 분석용 행을 남기나 정리 주체가 없어 무한 누적. 삭제 시점에 민감
     * 페이로드는 비워지므로 잔존 항목은 메타데이터뿐.
     */
    @Modifying
    @Query("delete from VmClusterEntity v where v.provisioningStatus = "
            + "com.aipaas.anycloud.domain.provisioning.model.VmClusterStatus.DELETED "
            + "and v.deletedAt < :cutoff")
    int deleteDeletedBefore(@Param("cutoff") LocalDateTime cutoff);
}
