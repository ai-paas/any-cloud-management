package com.aipaas.anycloud.service;

import com.aipaas.anycloud.service.Impl.ClusterServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class ClusterStatusScheduler {

  private final ClusterServiceImpl clusterServiceImpl;

  @Scheduled(fixedRate = 300000) // 5분마다 실행 (300000ms)
  public void updateClusterStatuses() {
    log.info("Starting scheduled cluster status update");
    // clusterServiceImpl.updateAllClusterStatuses(); // 공인인증 이후 주석 해제 예정
  }
}
