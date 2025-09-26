package com.aipaas.anycloud.service.util;

import com.aipaas.anycloud.error.exception.HelmDeploymentException;
import com.aipaas.anycloud.model.entity.HelmRepoEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

/**
 * <pre>
 * ClassName : ChartValidator
 * Type : class
 * Description : 차트 배포 전 사전 검증을 담당하는 유틸리티 클래스입니다.
 * Related : ChartServiceImpl
 * </pre>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChartValidator {

    private final HelmCommandExecutor helmCommandExecutor;

    /**
     * 릴리즈 이름 중복을 체크합니다 (비동기 실행 전 사전 검증).
     */
    public void checkReleaseNameDuplicate(String kubeconfigPath, String releaseName, String namespace) throws Exception {
        log.info("Checking release name duplicate for: {}", releaseName);
        
        // helm list 명령어로 기존 릴리즈 확인
        StringBuilder command = new StringBuilder();
        command.append("helm list --kubeconfig ").append(kubeconfigPath);
        
        if (namespace != null && !namespace.trim().isEmpty()) {
            command.append(" --namespace ").append(namespace);
        } else {
            command.append(" --all-namespaces");
        }
        
        command.append(" --output json");
        
        ProcessBuilder processBuilder = new ProcessBuilder();
        processBuilder.command("sh", "-c", command.toString());
        processBuilder.redirectErrorStream(true);
        
        Process process = processBuilder.start();
        
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
        }
        
        boolean finished = process.waitFor(30, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            log.warn("Helm list command timed out during release name check");
            return; // 타임아웃 시에는 체크를 건너뛰고 배포 진행
        }
        
        int exitCode = process.exitValue();
        if (exitCode != 0) {
            log.warn("Helm list command failed during release name check. Exit code: {}, Output: {}", 
                    exitCode, output.toString());
            return; // 실패 시에는 체크를 건너뛰고 배포 진행
        }
        
        String listOutput = output.toString();
        log.debug("Helm list output: {}", listOutput);
        
        // JSON 파싱해서 릴리즈 이름 확인
        if (listOutput.trim().isEmpty() || listOutput.equals("[]")) {
            log.info("No existing releases found. Release name {} is available.", releaseName);
            return;
        }
        
        // 간단한 문자열 검사로 릴리즈 이름 존재 여부 확인
        if (listOutput.contains("\"name\":\"" + releaseName + "\"")) {
            throw new HelmDeploymentException(
                "Release name '" + releaseName + "' already exists. " +
                "Please use a different release name or uninstall the existing release first.");
        }
        
        log.info("Release name {} is available for deployment.", releaseName);
    }

    /**
     * Helm repository 연결 상태를 확인합니다 (타임아웃 에러 사전 감지).
     */
    public void checkHelmRepositoryConnectivity(HelmRepoEntity repository) throws Exception {
        log.info("Checking Helm repository connectivity for: {}", repository.getName());
        
        // helm repo add + update 명령어로 repository 접근 테스트
        String repoAddCommand = helmCommandExecutor.buildHelmRepoAddCommand(repository);
        String updateCommand = "helm repo update " + repository.getName();
        String combinedCommand = repoAddCommand + " && " + updateCommand;
        
        ProcessBuilder processBuilder = new ProcessBuilder();
        processBuilder.command("sh", "-c", combinedCommand);
        processBuilder.redirectErrorStream(true);
        
        Process process = processBuilder.start();
        
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
        }
        
        boolean finished = process.waitFor(20, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new HelmDeploymentException(
                "Helm repository connection timeout. Repository '" + repository.getName() + 
                "' is not responding within 20 seconds. Please check repository URL: " + repository.getUrl());
        }
        
        int exitCode = process.exitValue();
        String commandOutput = output.toString();
        
        if (exitCode != 0) {
            log.error("Helm repository connectivity test failed for: {}. Output: {}", repository.getName(), commandOutput);
            
            if (commandOutput.contains("context deadline exceeded") || 
                commandOutput.contains("timeout") || 
                commandOutput.contains("connection timed out")) {
                throw new HelmDeploymentException(
                    "Helm repository connection timeout. Repository '" + repository.getName() + 
                    "' is not responding. Please check repository URL and network connectivity. " +
                    "Error details: " + commandOutput);
            } else if (commandOutput.contains("connection refused") || 
                      commandOutput.contains("no such host") ||
                      commandOutput.contains("network unreachable")) {
                throw new HelmDeploymentException(
                    "Helm repository connection failed. Cannot reach repository '" + repository.getName() + 
                    "' at URL: " + repository.getUrl() + ". Please check repository URL and network connectivity. " +
                    "Error details: " + commandOutput);
            } else {
                throw new HelmDeploymentException(
                    "Helm repository test failed for '" + repository.getName() + "'. " +
                    "Error details: " + commandOutput);
            }
        }
        
        log.info("Helm repository connectivity test passed for: {}", repository.getName());
    }

    /**
     * 클러스터 정보가 유효한지 확인합니다.
     */
    public void validateClusterInfo(String clusterId, String version) throws HelmDeploymentException {
        if (clusterId == null || clusterId.trim().isEmpty()) {
            throw new HelmDeploymentException("Cluster ID is required for chart deployment");
        }
        
        if (version == null || version.isEmpty() || version.equals("UNKNOWN")) {
            throw new HelmDeploymentException("Cluster status is unknown for cluster: " + clusterId);
        }
        
        log.debug("Cluster validation passed for cluster: {}", clusterId);
    }

    /**
     * 배포 파라미터가 유효한지 확인합니다.
     */
    public void validateDeploymentParameters(String repositoryName, String chartName, String releaseName, String clusterId) 
            throws HelmDeploymentException {
        if (repositoryName == null || repositoryName.trim().isEmpty()) {
            throw new HelmDeploymentException("Repository name is required");
        }
        
        if (chartName == null || chartName.trim().isEmpty()) {
            throw new HelmDeploymentException("Chart name is required");
        }
        
        if (releaseName == null || releaseName.trim().isEmpty()) {
            throw new HelmDeploymentException("Release name is required");
        }
        
        if (clusterId == null || clusterId.trim().isEmpty()) {
            throw new HelmDeploymentException("Cluster ID is required");
        }
        
        // 릴리즈 이름 규칙 검증 (Kubernetes naming convention)
        if (!releaseName.matches("^[a-z0-9]([-a-z0-9]*[a-z0-9])?$")) {
            throw new HelmDeploymentException(
                "Invalid release name format. Release name must contain only lowercase letters, " +
                "numbers and hyphens, and must start and end with alphanumeric characters.");
        }
        
        log.debug("Deployment parameters validation passed");
    }

    /**
     * 네임스페이스 이름이 유효한지 확인합니다.
     */
    public void validateNamespace(String namespace) throws HelmDeploymentException {
        if (namespace != null && !namespace.trim().isEmpty()) {
            // 네임스페이스 이름 규칙 검증
            if (!namespace.matches("^[a-z0-9]([-a-z0-9]*[a-z0-9])?$")) {
                throw new HelmDeploymentException(
                    "Invalid namespace format. Namespace must contain only lowercase letters, " +
                    "numbers and hyphens, and must start and end with alphanumeric characters.");
            }
            
            if (namespace.length() > 63) {
                throw new HelmDeploymentException("Namespace name cannot exceed 63 characters");
            }
        }
        
        log.debug("Namespace validation passed for: {}", namespace);
    }

    /**
     * 전체 배포 사전 검증을 수행합니다.
     */
    public void validateBeforeDeployment(String repositoryName, String chartName, String releaseName, 
            String clusterId, String namespace, String clusterVersion, String kubeconfigPath, 
            HelmRepoEntity repository) throws Exception {
        
        log.info("Starting comprehensive validation for deployment: {}/{} as {} to cluster {}", 
                repositoryName, chartName, releaseName, clusterId);
        
        // 1. 기본 파라미터 검증
        validateDeploymentParameters(repositoryName, chartName, releaseName, clusterId);
        
        // 2. 네임스페이스 검증
        validateNamespace(namespace);
        
        // 3. 클러스터 정보 검증
        validateClusterInfo(clusterId, clusterVersion);

        // 4. Repository 연결 확인
        checkHelmRepositoryConnectivity(repository);
        
        // 5. 릴리즈 이름 중복 체크
        checkReleaseNameDuplicate(kubeconfigPath, releaseName, namespace);
        
 
        
        log.info("All validation checks passed for deployment: {}/{}", repositoryName, chartName);
    }
}
