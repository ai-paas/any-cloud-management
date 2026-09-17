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
     * 처리 소유권을 잡는다. 이미 다른 메시지가 잡고 있으면 0 을 돌려준다.
     *
     * <p>조건부 UPDATE 한 번으로 끝내야 한다 — 읽고 나서 쓰면 두 워커가 같은 틈에 들어온다.
     * 같은 messageId 면 재진입을 허용해 한 워커의 재시도가 자기 락에 막히지 않는다.
     * staleBefore 보다 오래된 소유권은 워커가 죽은 것으로 보고 회수한다.
     */
    /**
     * AMQP 재전달은 같은 messageId 를 쓴다. 조건에 {@code processingMessageId = :messageId} 를 두면
     * 재전달이 자기 락을 다시 잡아 같은 작업이 두 번 돈다 — 30분마다 부트스트랩이 처음부터 다시
     * 시작하는 것을 관측했다.
     *
     * <p>크래시 후 재시도는 stale 창이 열어준다. 같은 messageId 라는 이유만으로 열어주지 않는다.
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
     * 누가 쥐고 있든 소유권을 가져온다. 삭제 전용이다.
     *
     * <p>stale 창을 단계 예산에 맞추면 삭제가 그 뒤에 줄을 선다 — 멈춘 프로비저닝을 지우려는데
     * 몇 시간을 기다려야 했다. 앞 단계의 결과는 어차피 버려지므로 밀어내도 잃는 것이 없다.
     */
    @Modifying
    @Query("update VmClusterEntity v set v.processingMessageId = :messageId, v.processingStartedAt = :now "
            + "where v.id = :id")
    int takeOverProcessing(
            @Param("id") String id, @Param("messageId") String messageId, @Param("now") LocalDateTime now);

    /** 자기가 잡은 소유권만 놓는다. 회수된 뒤라면 아무것도 바꾸지 않는다. */
    @Modifying
    @Query("update VmClusterEntity v set v.processingMessageId = null, v.processingStartedAt = null "
            + "where v.id = :id and v.processingMessageId = :messageId")
    int releaseProcessing(@Param("id") String id, @Param("messageId") String messageId);

    /** 조정 루프 대상 — READY / DEGRADED 클러스터. */
    List<VmClusterEntity> findByProvisioningStatusIn(
            java.util.Collection<com.aipaas.anycloud.domain.provisioning.model.VmClusterStatus> statuses);

    Optional<VmClusterEntity> findFirstByClusterNameOrderByCreatedAtDesc(String clusterName);

    /** 같은 이름의 모든 세대. 재시도로 쌓인 옛 행까지 함께 정리할 때 쓴다. */
    List<VmClusterEntity> findAllByClusterNameOrderByCreatedAtDesc(String clusterName);

    List<VmClusterEntity> findAllByOrderByCreatedAtDesc();

    boolean existsByActiveRequestKey(String activeRequestKey);

    long countByCredentialIdAndActiveRequestKeyIsNotNull(String credentialId);

    /**
     * 보관 기한이 지난 삭제 이력을 걷어낸다.
     *
     * <p>삭제는 감사와 원인 분석을 위해 행을 남기는데 정리하는 곳이 없어 영원히 쌓였다.
     * 삭제 시점에 민감 페이로드는 이미 비워지므로 남는 것은 메타데이터뿐이다.
     */
    @Modifying
    @Query("delete from VmClusterEntity v where v.provisioningStatus = "
            + "com.aipaas.anycloud.domain.provisioning.model.VmClusterStatus.DELETED "
            + "and v.deletedAt < :cutoff")
    int deleteDeletedBefore(@Param("cutoff") LocalDateTime cutoff);
}
