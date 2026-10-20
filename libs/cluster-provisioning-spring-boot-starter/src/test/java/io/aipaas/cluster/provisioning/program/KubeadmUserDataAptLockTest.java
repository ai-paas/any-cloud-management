package io.aipaas.cluster.provisioning.program;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 부팅 직후 apt 락 경쟁.
 *
 * <p>클라우드 이미지는 첫 부팅에 unattended-upgrades 로 apt 실행. 락 대기 없이 진행하면
 * {@code apt-get update} 가 즉시 "Could not get lock" 으로 실패하고 {@code set -e} 가 스크립트
 * 전체를 중단해 containerd 와 kubelet 미설치. VM 은 정상 기동하므로 부트스트랩 정지 시점까지
 * 드러나지 않음.
 */
class KubeadmUserDataAptLockTest {

    private ClusterSpec spec() {
        Map<String, String> cfg = new HashMap<>();
        cfg.put("provider", "aws");
        cfg.put("name", "demo");
        cfg.put("workerCount", "1");
        cfg.put("joinToken", "abcdef.0123456789abcdef");
        return Defaults.applyProviderDefaults(ClusterSpec.from(cfg));
    }

    private List<String> aptLines(String script) {
        return script.lines()
                .map(String::strip)
                .filter(line -> !line.startsWith("#"))
                .filter(line -> line.contains("apt-get "))
                .toList();
    }

    @Test
    void everyAptInvocationWaitsForTheLock() {
        for (String script : new String[] {KubeadmUserData.master(spec()), KubeadmUserData.worker(spec())}) {
            assertThat(aptLines(script)).allSatisfy(line -> assertThat(line)
                    .as("락을 기다리지 않는 apt 호출: %s", line)
                    .contains("apt_wait &&")
                    .contains("$APT_LOCK"));
        }
    }

    @Test
    void lockWaitCoversTheListsLock() {
        // DPkg::Lock::Timeout 은 dpkg 락만 덮는다. apt-get update 가 잡는 lists/lock 은 별도로
        // 기다려야 한다. 옵션만 믿었다가 worker 노드에서 그대로 실패했다.
        String script = KubeadmUserData.master(spec());

        assertThat(script)
                .contains("/var/lib/apt/lists/lock")
                .contains("/var/lib/dpkg/lock-frontend")
                .containsPattern("APT_LOCK='[^']*DPkg::Lock::Timeout=\\d+[^']*'");
    }

    @Test
    void lockWaitIsBounded() {
        // 상한이 없으면 락이 안 풀릴 때 cloud-init 이 조용히 멈춘다.
        assertThat(KubeadmUserData.master(spec())).containsPattern("for _ in \\$\\(seq 1 \\d+\\); do");
    }

    @Test
    void aptIsActuallyInvoked() {
        // 위 검사들이 apt 호출 부재 상태로 무음 통과하는 것을 차단.
        assertThat(aptLines(KubeadmUserData.master(spec()))).hasSizeGreaterThanOrEqualTo(4);
    }
}
