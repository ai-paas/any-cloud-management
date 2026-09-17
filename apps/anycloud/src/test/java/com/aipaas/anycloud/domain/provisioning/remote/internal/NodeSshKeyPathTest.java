package com.aipaas.anycloud.domain.provisioning.remote.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 터미널 세션마다 개인키를 임시 파일로 떨군다. 파일명이 요청 값에서 오면 런타임 디렉터리를
 * 벗어나는 통로가 되고, 그 경로가 ssh 인자로도 흘러간다.
 */
class NodeSshKeyPathTest extends AbstractUnitTest {

    @Test
    void theKeyLandsInsideTheRuntimeDirectory(@TempDir Path dir) throws IOException {
        Path keyPath = VmClusterNodeSshSessionServiceImpl.createKeyFile(dir);

        assertThat(keyPath.getParent()).isEqualTo(dir);
        assertThat(keyPath).exists();
    }

    @Test
    void theNameCarriesNothingFromTheRequest(@TempDir Path dir) throws IOException {
        Path keyPath = VmClusterNodeSshSessionServiceImpl.createKeyFile(dir);

        assertThat(keyPath.getFileName().toString()).startsWith("node-term-").endsWith(".pem");
    }

    @Test
    void concurrentSessionsDoNotShareAKeyFile(@TempDir Path dir) throws IOException {
        // 같은 클러스터에 터미널을 둘 열면, 이름이 겹칠 경우 한쪽이 끝나며 다른 쪽 키를 지운다.
        Path first = VmClusterNodeSshSessionServiceImpl.createKeyFile(dir);
        Path second = VmClusterNodeSshSessionServiceImpl.createKeyFile(dir);

        assertThat(first).isNotEqualTo(second);
    }
}
