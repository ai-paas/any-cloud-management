package com.aipaas.anycloud.service.util;

import com.aipaas.anycloud.error.exception.HelmDeploymentException;
import com.aipaas.anycloud.model.entity.HelmRepoEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * <pre>
 * ClassName : HelmCommandExecutor
 * Type : class
 * Description : Helm 명령어 실행 및 빌드를 담당하는 유틸리티 클래스입니다.
 * Related : ChartServiceImpl
 * </pre>
 */
@Slf4j
@Component
public class HelmCommandExecutor {

    /**
     * kubeconfig를 사용하여 Helm 명령어를 실행합니다.
     */
    public String executeHelmCommand(String command, String kubeconfigPath) throws IOException, InterruptedException {
        log.debug("Executing helm command: {}", command);

        ProcessBuilder processBuilder = new ProcessBuilder();
        processBuilder.command("sh", "-c", command);
        processBuilder.redirectErrorStream(true);

        // kubeconfig 환경변수 설정
        Map<String, String> environment = processBuilder.environment();
        environment.put("KUBECONFIG", kubeconfigPath);

        Process process = processBuilder.start();

        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
        }

        boolean finished = process.waitFor(60, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new HelmDeploymentException("Helm command timed out: " + command);
        }

        int exitCode = process.exitValue();
        String commandOutput = output.toString();
        
        // 디버깅을 위한 상세 로깅
        log.info("Helm command executed. Exit code: {}, Output length: {}", exitCode, commandOutput.length());
        log.debug("Helm command output: {}", commandOutput);
        
        // exit code와 관계없이 에러 패턴도 확인 (일부 Helm 명령어는 에러가 있어도 exit code 0을 반환할 수 있음)
        boolean hasError = exitCode != 0 || 
                          commandOutput.contains("Error:") || 
                          commandOutput.contains("INSTALLATION FAILED") ||
                          commandOutput.contains("FAILED");
        
        if (hasError) {
            log.error("Helm command error detected. Exit code: {}, Output: {}", exitCode, commandOutput);
            
            // 특정 에러 패턴 감지 및 맞춤형 에러 메시지 제공
            if (commandOutput.contains("cannot re-use a name that is still in use")) {
                throw new HelmDeploymentException(
                    "Release name already exists. Please use a different release name or uninstall the existing release first. " +
                    "Error details: " + commandOutput);
            } else if (commandOutput.contains("tls: failed to verify certificate")) {
                throw new HelmDeploymentException(
                    "TLS certificate verification failed. Please check cluster certificate configuration. " +
                    "Error details: " + commandOutput);
            } else if (commandOutput.contains("connection refused") || commandOutput.contains("unable to connect")) {
                throw new HelmDeploymentException(
                    "Unable to connect to Kubernetes cluster. Please check cluster connectivity. " +
                    "Error details: " + commandOutput);
            } else {
                throw new HelmDeploymentException(
                    "Helm command failed with exit code " + exitCode + ": " + commandOutput);
            }
        }

        return output.toString();
    }

    /**
     * kubeconfig 없이 Helm 명령어를 실행합니다.
     */
    public String executeHelmCommandWithoutKubeconfig(String command) throws IOException, InterruptedException {
        log.debug("Executing helm command (without kubeconfig): {}", command);

        ProcessBuilder processBuilder = new ProcessBuilder();
        processBuilder.command("sh", "-c", command);
        processBuilder.redirectErrorStream(true);

        Process process = processBuilder.start();

        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
        }

        boolean finished = process.waitFor(60, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new HelmDeploymentException("Helm command timed out: " + command);
        }

        int exitCode = process.exitValue();
        if (exitCode != 0) {
            throw new HelmDeploymentException(
                    "Helm command failed with exit code " + exitCode + ": " + output.toString());
        }

        return output.toString();
    }

    /**
     * Helm show 명령어를 빌드합니다.
     */
    public String buildHelmShowCommand(String showType, HelmRepoEntity repository, String chartName, String version) {
        StringBuilder command = new StringBuilder();
        
        // 먼저 repository를 추가
        String repoAddCommand = buildHelmRepoAddCommand(repository);
        command.append(repoAddCommand).append(" && ");
        
        command.append("helm show ").append(showType).append(" ")
                .append(repository.getName()).append("/").append(chartName);
        
        if (version != null && !version.trim().isEmpty()) {
            command.append(" --version ").append(version);
        }

        return command.toString();
    }

    /**
     * Helm repository add 명령어를 빌드합니다.
     * 이미 추가된 저장소인 경우 건너뛰는 로직이 포함됩니다.
     */
    public String buildHelmRepoAddCommand(HelmRepoEntity repository) {
        StringBuilder command = new StringBuilder();
        
        // 먼저 저장소가 이미 존재하는지 확인하고, 없을 때만 추가
        command.append("(helm repo list | grep -q '^")
                .append(repository.getName())
                .append("\\s' && echo 'Repository ")
                .append(repository.getName())
                .append(" already exists, skipping...') || (");
        
        // 실제 helm repo add 명령어
        command.append("helm repo add ")
                .append(repository.getName())
                .append(" ")
                .append(repository.getUrl());

        // 인증 정보가 있으면 추가
        if (repository.getUsername() != null && !repository.getUsername().trim().isEmpty()) {
            command.append(" --username ").append(repository.getUsername());
        }

        if (repository.getPassword() != null && !repository.getPassword().trim().isEmpty()) {
            command.append(" --password ").append(repository.getPassword());
        }

        // TLS 검증 생략 처리
        if (repository.getInsecureSkipTlsVerify() != null && repository.getInsecureSkipTlsVerify()) {
            command.append(" --insecure-skip-tls-verify");
            log.debug("Added insecure-skip-tls-verify for repository: {}", repository.getName());
        }
        
        // 괄호 닫기
        command.append(")");

        log.debug("Built helm repo add command with duplicate check for repository: {}", repository.getName());
        return command.toString();
    }

    /**
     * Helm install 명령어를 빌드합니다.
     */
    public String buildHelmInstallCommand(HelmRepoEntity repository, String chartName, String releaseName,
            String namespace, String version, MultipartFile valuesFile, String kubeconfigPath) {
        // 먼저 repository를 추가
        String repoAddCommand = buildHelmRepoAddCommand(repository);

        StringBuilder command = new StringBuilder();
        command.append(repoAddCommand).append(" && ");
        command.append("helm install ")
                .append(releaseName)
                .append(" ")
                .append(repository.getName())
                .append("/")
                .append(chartName);

        // kubeconfig 파일 지정
        command.append(" --kubeconfig ").append(kubeconfigPath);

        if (namespace != null && !namespace.trim().isEmpty()) {
            command.append(" --namespace ").append(namespace)
                    .append(" --create-namespace");
        }

        if (version != null && !version.trim().isEmpty()) {
            command.append(" --version ").append(version);
        } else {
            // 버전이 지정되지 않으면 최신 버전 사용
            log.info("No version specified, using latest version");
        }

        // values 파일이 있으면 사용
        if (valuesFile != null && !valuesFile.isEmpty() && valuesFile.getSize() > 0) {
            try {
                // 임시 파일로 저장
                String tempValuesPath = saveValuesFile(valuesFile);
                command.append(" --values ").append(tempValuesPath);
                log.info("Using values file: {}", tempValuesPath);
            } catch (IOException e) {
                log.error("Failed to save values file", e);
                throw new HelmDeploymentException("Failed to process values file: " + e.getMessage());
            }
        } else {
            log.info("No values file provided, using default values");
        }

        // 배포 옵션 추가 (timeout 제거하여 호환성 확보)
        
        // TLS 검증 건너뛰기 (자체 서명된 인증서 또는 인증서 없는 클러스터 지원)
        command.append(" --insecure-skip-tls-verify");

        return command.toString();
    }

    /**
     * Helm status 명령어를 빌드합니다.
     */
    public String buildHelmStatusCommand(String releaseName, String namespace, String kubeconfigPath) {
        StringBuilder command = new StringBuilder();
        command.append("helm status ")
                .append(releaseName);

        // kubeconfig 파일 지정
        command.append(" --kubeconfig ").append(kubeconfigPath);

        if (namespace != null && !namespace.trim().isEmpty()) {
            command.append(" --namespace ").append(namespace);
        }

        return command.toString();
    }

    /**
     * Helm list 명령어를 빌드합니다.
     */
    public String buildHelmListCommand(String namespace, String kubeconfigPath) {
        StringBuilder command = new StringBuilder();
        command.append("helm list --kubeconfig ").append(kubeconfigPath);

        if (namespace != null && !namespace.trim().isEmpty()) {
            command.append(" --namespace ").append(namespace);
        } else {
            command.append(" --all-namespaces");
        }

        command.append(" --output json");
        return command.toString();
    }

    /**
     * MultipartFile을 임시 파일로 저장합니다.
     */
    private String saveValuesFile(MultipartFile valuesFile) throws IOException {
        String tempDir = System.getProperty("java.io.tmpdir");
        String fileName = "values-" + System.currentTimeMillis() + ".yaml";
        Path tempFile = Paths.get(tempDir, fileName);

        Files.write(tempFile, valuesFile.getBytes());
        log.info("Saved values file to: {}", tempFile.toString());

        return tempFile.toString();
    }
}
