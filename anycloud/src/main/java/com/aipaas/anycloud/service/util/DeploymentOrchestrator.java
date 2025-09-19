package com.aipaas.anycloud.service.util;

import com.aipaas.anycloud.model.entity.ClusterEntity;
import com.aipaas.anycloud.model.entity.HelmRepoEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <pre>
 * ClassName : DeploymentOrchestrator
 * Type : class
 * Description : Helm 차트 비동기 배포 관리를 담당하는 클래스입니다.
 * 관련 기능 :
 *  - 비동기 배포 실행 관리
 *  - ExecutorService 풀 관리
 *  - 배포 작업 오케스트레이션
 *  - 배포 상태 추적 및 로깅
 * </pre>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DeploymentOrchestrator {

    private final HelmCommandExecutor helmCommandExecutor;
    
    // 비동기 처리를 위한 ExecutorService
    private final ExecutorService executorService = Executors.newFixedThreadPool(5);

    /**
     * 비동기로 Helm 차트 배포를 실행합니다.
     * 
     * @param repository Helm 저장소 정보
     * @param chartName 차트명
     * @param releaseName 릴리즈명
     * @param clusterId 클러스터 ID
     * @param namespace 네임스페이스
     * @param version 차트 버전
     * @param valuesFile 값 파일
     * @param cluster 클러스터 엔티티
     * @param kubeconfigCreator kubeconfig 생성 함수
     * @param kubeconfigDeleter kubeconfig 삭제 함수
     */
    public void executeDeploymentAsync(HelmRepoEntity repository, String chartName, String releaseName,
            String clusterId, String namespace, String version, MultipartFile valuesFile, ClusterEntity cluster,
            KubeconfigCreator kubeconfigCreator, KubeconfigDeleter kubeconfigDeleter) {
        
        log.info("Submitting async deployment task for chart: {}/{} as release: {} to cluster: {}",
                repository.getName(), chartName, releaseName, clusterId);

        CompletableFuture.runAsync(() -> {
            executeDeployment(repository, chartName, releaseName, clusterId, namespace, version, valuesFile,
                    cluster, kubeconfigCreator, kubeconfigDeleter);
        }, executorService);
    }

    /**
     * 실제 배포 작업을 수행합니다.
     */
    private void executeDeployment(HelmRepoEntity repository, String chartName, String releaseName,
            String clusterId, String namespace, String version, MultipartFile valuesFile, ClusterEntity cluster,
            KubeconfigCreator kubeconfigCreator, KubeconfigDeleter kubeconfigDeleter) {
        
        log.info("Executing async deployment for chart: {}/{} as release: {} to cluster: {}",
                repository.getName(), chartName, releaseName, clusterId);

        try {
            // kubeconfig 파일 생성
            String kubeconfigPath = kubeconfigCreator.create(cluster);

            try {
                // Helm CLI를 사용하여 차트 배포 (kubeconfig 사용)
                String command = helmCommandExecutor.buildHelmInstallCommand(repository, chartName, releaseName, 
                        namespace, version, valuesFile, kubeconfigPath);
                helmCommandExecutor.executeHelmCommand(command, kubeconfigPath);

                log.info("Successfully deployed chart: {}/{} as release: {} to cluster: {}",
                        repository.getName(), chartName, releaseName, clusterId);
                        
            } finally {
                // 임시 kubeconfig 파일 삭제
                kubeconfigDeleter.delete(kubeconfigPath);
            }

        } catch (Exception e) {
            log.error("Failed to deploy chart: {}/{} to cluster: {} in async execution", 
                    repository.getName(), chartName, clusterId, e);
            // 여기서 추가적인 에러 핸들링이나 알림 로직을 추가할 수 있습니다.
        }
    }

    /**
     * ExecutorService 종료 시 호출되는 메서드입니다.
     * 애플리케이션 종료 시 graceful shutdown을 위해 사용됩니다.
     */
    public void shutdown() {
        log.info("Shutting down DeploymentOrchestrator ExecutorService");
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS)) {
                executorService.shutdownNow();
                log.warn("ExecutorService did not terminate gracefully, forced shutdown");
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
            log.error("Interrupted while waiting for ExecutorService termination", e);
        }
    }

    /**
     * kubeconfig 파일 생성을 위한 함수형 인터페이스
     */
    @FunctionalInterface
    public interface KubeconfigCreator {
        String create(ClusterEntity cluster) throws Exception;
    }

    /**
     * kubeconfig 파일 삭제를 위한 함수형 인터페이스
     */
    @FunctionalInterface
    public interface KubeconfigDeleter {
        void delete(String kubeconfigPath);
    }
}
