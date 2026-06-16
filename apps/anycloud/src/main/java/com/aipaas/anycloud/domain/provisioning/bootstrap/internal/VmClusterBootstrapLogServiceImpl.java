package com.aipaas.anycloud.domain.provisioning.bootstrap.internal;

import com.aipaas.anycloud.domain.provisioning.VmClusterEntity;
import com.aipaas.anycloud.domain.provisioning.bootstrap.VmClusterBootstrapLogService;
import com.aipaas.anycloud.domain.provisioning.remote.VmClusterRemoteAccessService;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class VmClusterBootstrapLogServiceImpl implements VmClusterBootstrapLogService {

    /**
     * MEDIUMTEXT 컬럼 cap. 한 cluster 의 누적 bootstrap log 가 이 길이를 넘으면 가장 오래된
     * attempt 부터 잘라낸다. 256KB — 보통 cluster 당 retry 5회 정도까지 보관 가능.
     */
    private static final int MAX_LOG_BYTES = 256 * 1024;

    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final VmClusterRemoteAccessService vmClusterRemoteAccessService;

    /**
     * Bootstrap log 안에 우연히 포함될 수 있는 민감 정보 패턴들. SSH key 자체는 cloud-init 으로
     * VM 에 placement 되며 user-data 가 stdout 으로 echo 하지 않지만, 향후 cloud-init 변경 /
     * verbose kubelet log / kubeadm verbose mode 에서 PEM 이나 token 이 우연히 포함될 가능성에
     * 대한 defense-in-depth.
     */
    private static final java.util.regex.Pattern PEM_BLOCK_PATTERN = java.util.regex.Pattern.compile(
            "-----BEGIN [A-Z ]+-----.*?-----END [A-Z ]+-----", java.util.regex.Pattern.DOTALL);

    /**
     * kubeadm token / Bearer JWT 비슷한 string. kubeadm token 형식: {@code [a-z0-9]{6}\.[a-z0-9]{16}}.
     * 너무 일반적이라 false positive 있을 수 있으나 PEM 처럼 stable identifier 가 들어가는 경우만 매칭.
     */
    private static final java.util.regex.Pattern KUBEADM_TOKEN_PATTERN =
            java.util.regex.Pattern.compile("(?<![a-z0-9])[a-z0-9]{6}\\.[a-z0-9]{16}(?![a-z0-9])");

    @Override
    public String collectBootstrapLog(VmClusterEntity vmCluster, Map<String, Object> outputs) {
        try {
            return maskSensitive(collect(vmCluster, outputs));
        } catch (Exception e) {
            log.warn("Failed to collect bootstrap log for cluster {}: {}", vmCluster.getClusterName(), e.getMessage());
            return null;
        }
    }

    /**
     * Bootstrap log 출력에서 PEM block / kubeadm token 패턴을 mask. 응답으로 나가기 전 적용.
     * package-private for test visibility.
     */
    static String maskSensitive(String raw) {
        if (raw == null || raw.isBlank()) {
            return raw;
        }
        String masked = PEM_BLOCK_PATTERN.matcher(raw).replaceAll("-----REDACTED-PEM-BLOCK-----");
        masked = KUBEADM_TOKEN_PATTERN.matcher(masked).replaceAll("REDACTED-TOKEN");
        return masked;
    }

    @Override
    public void appendAttemptMarker(VmClusterEntity vmCluster, int attemptNumber) {
        String marker = String.format(
                "%n=== attempt %d — %s ===%n",
                Math.max(1, attemptNumber), LocalDateTime.now().format(TIMESTAMP_FORMAT));
        String existing = vmCluster.getBootstrapLog();
        String combined = existing == null ? marker.stripLeading() : existing + marker;
        vmCluster.setBootstrapLog(capLog(combined));
    }

    @Override
    public void appendDiagnostics(VmClusterEntity vmCluster, Map<String, Object> outputs) {
        String diagnostics = collectBootstrapLog(vmCluster, outputs);
        if (diagnostics == null || diagnostics.isBlank()) {
            return;
        }
        String existing = vmCluster.getBootstrapLog();
        String combined = (existing == null || existing.isBlank())
                ? diagnostics
                : existing + System.lineSeparator() + diagnostics;
        vmCluster.setBootstrapLog(capLog(combined));
    }

    /**
     * 누적 log 의 앞부분 (오래된 attempt) 부터 잘라 cap 이하로 유지. 잘렸음을 표시하는
     * sentinel 한 줄을 prefix.
     */
    private String capLog(String value) {
        if (value == null || value.length() <= MAX_LOG_BYTES) {
            return value;
        }
        int dropFrom = value.length() - MAX_LOG_BYTES;
        String tail = value.substring(dropFrom);
        // attempt 경계에서 자르도록 정렬 — 다음 "=== attempt" 부터 보존.
        int firstAttempt = tail.indexOf("=== attempt");
        if (firstAttempt > 0) {
            tail = tail.substring(firstAttempt);
        }
        return "...(older attempts truncated)" + System.lineSeparator() + tail;
    }

    private String collect(VmClusterEntity vmCluster, Map<String, Object> outputs) throws Exception {
        String host = firstNonBlank(
                stringValue(outputs.get("masterPublicDns")),
                stringValue(outputs.get("masterPublicIp")),
                stringValue(outputs.get("masterPrivateIp")));
        if (host == null || host.isBlank()) {
            return null;
        }

        String command =
                "echo \"=== cloud-init-output ===\"; " + "sudo cat /var/log/cloud-init-output.log 2>/dev/null || true; "
                        + "echo; echo \"=== cloud-init status ===\"; "
                        + "sudo cloud-init status --long 2>/dev/null || true; "
                        + "echo; echo \"=== kubelet journal ===\"; "
                        + "sudo journalctl -u kubelet -n 200 --no-pager 2>/dev/null || true; "
                        + "echo; echo \"=== kubelet systemd status ===\"; "
                        + "sudo systemctl --no-pager --full status kubelet 2>/dev/null || true; "
                        + "echo; echo \"=== kubernetes nodes ===\"; "
                        + "sudo KUBECONFIG=/etc/kubernetes/admin.conf kubectl get nodes -o wide 2>/dev/null || true; "
                        + "echo; echo \"=== kubernetes pods ===\"; "
                        + "sudo KUBECONFIG=/etc/kubernetes/admin.conf kubectl get pods -A 2>/dev/null || true; "
                        + "echo; echo \"=== kubeadm init log files ===\"; "
                        + "sudo find /var/log -maxdepth 2 -type f \\( -name \"*kube*\" -o -name \"*cloud-init*\" \\) 2>/dev/null | xargs -r sudo tail -n 50 2>/dev/null || true";
        String output =
                vmClusterRemoteAccessService.runOnHost(vmCluster, outputs, host, command, Duration.ofMinutes(2));
        return truncate(output, 16000);
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + System.lineSeparator() + "...(truncated)";
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
