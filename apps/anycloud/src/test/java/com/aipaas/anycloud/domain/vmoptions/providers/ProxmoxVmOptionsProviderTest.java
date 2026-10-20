package com.aipaas.anycloud.domain.vmoptions.providers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.aipaas.anycloud.domain.provisioning.proxmox.ProxmoxApiClient;
import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * 노드 이름, datastore, 브리지는 호스트별 상이.
 *
 * <p>사용자가 콘솔에서 확인해 직접 입력하면 오타가 프로비저닝 단계까지 드러나지 않음. datastore
 * 는 종류를 잘못 선택하면(블록에 이미지, 디렉터리에 디스크) PVE 가 거절.
 */
@MockitoSettings(strictness = Strictness.LENIENT)
class ProxmoxVmOptionsProviderTest extends AbstractUnitTest {

    private static final String NODES =
            """
            [{"node":"proxmox","status":"online"},{"node":"pve2","status":"online"}]""";
    private static final String STORAGE =
            """
            [{"storage":"local","content":"backup,iso,import,vztmpl"},
             {"storage":"local-lvm","content":"rootdir,images"},
             {"storage":"NVME_1TB","content":"images,rootdir"}]""";
    private static final String NETWORK =
            """
            [{"type":"bridge","iface":"vmbr0"},{"type":"eth","iface":"enp4s0"},{"type":"bridge","iface":"vmbr1"}]""";

    @Mock
    ProxmoxApiClient api;

    private final ObjectMapper mapper = new ObjectMapper();

    private ProxmoxVmOptionsProvider provider() throws Exception {
        when(api.get(any(), eq("/api2/json/nodes"))).thenReturn(mapper.readTree(NODES));
        when(api.get(any(), eq("/api2/json/nodes/proxmox/storage"))).thenReturn(mapper.readTree(STORAGE));
        when(api.get(any(), eq("/api2/json/nodes/proxmox/network"))).thenReturn(mapper.readTree(NETWORK));
        return new ProxmoxVmOptionsProvider(api);
    }

    private List<String> options(String key) throws Exception {
        return provider().listConfigOptions(key, "proxmox");
    }

    @Test
    void nodeNamesComeFromTheHost() throws Exception {
        assertThat(options("providerSpec.nodeName")).containsExactly("proxmox", "pve2");
    }

    @Test
    void diskDatastoresOnlyListBlockStorage() throws Exception {
        // 디렉터리 스토리지(local)에 디스크 생성 시도 시 PVE 거절.
        assertThat(options("providerSpec.datastoreId")).containsExactly("local-lvm", "NVME_1TB");
    }

    @Test
    void imageDatastoresOnlyListDirectoriesThatAcceptImport() throws Exception {
        // 블록 스토리지는 import content 미허용이라 이미지 다운로드 실패.
        assertThat(options("providerSpec.imageDatastoreId")).containsExactly("local");
    }

    @Test
    void onlyBridgesAreOfferedAsNetworks() throws Exception {
        // 물리 인터페이스 선택 시 VM 연결 불가.
        assertThat(options("providerSpec.networkBridge")).containsExactly("vmbr0", "vmbr1");
    }

    @Test
    void anUnknownKeyOffersNothing() throws Exception {
        assertThat(options("providerSpec.somethingElse")).isEmpty();
    }

    @Test
    void withoutANodeTheFirstOneIsUsed() throws Exception {
        // 화면이 아직 노드를 고르지 않았을 때다. 단일 노드 호스트가 대부분이라 빈 목록보다 낫다.
        assertThat(provider().listConfigOptions("providerSpec.networkBridge", null))
                .containsExactly("vmbr0", "vmbr1");
    }

    @Test
    void regionsAreTheNodesBecauseAHypervisorHasNone() throws Exception {
        assertThat(provider().listRegions()).extracting("id").containsExactly("proxmox", "pve2");
    }

    @Test
    void thereIsNoInstanceTypeCatalog() throws Exception {
        // Proxmox 는 "코어-메모리MiB" 를 직접 수신. 목록을 임의 구성하면 미제공 항목이 선택지에 포함.
        assertThat(provider().listSpecs("proxmox", null, false, 100)).isEmpty();
        assertThat(provider().listImages("proxmox", null, null, null, 100)).isEmpty();
    }

    @Test
    void anUnreachableHostLeavesTheFieldFree() throws Exception {
        // 목록 제공 실패가 생성 차단 사유는 아님. 자유 입력으로 유지.
        when(api.get(any(), any())).thenReturn(null);

        assertThat(new ProxmoxVmOptionsProvider(api).listConfigOptions("providerSpec.nodeName", "proxmox"))
                .isEmpty();
    }

    @Test
    void credentialKeysAreReadFromTheRegisteredCredential() throws Exception {
        // env fallback 전환 시 다른 호스트를 조회하거나 빈 목록 반환.
        ProxmoxVmOptionsProvider p = provider();
        p.listConfigOptions("providerSpec.nodeName", "proxmox");

        org.mockito.ArgumentCaptor<Map<String, String>> env = org.mockito.ArgumentCaptor.forClass(Map.class);
        org.mockito.Mockito.verify(api, org.mockito.Mockito.atLeastOnce()).get(env.capture(), eq("/api2/json/nodes"));
        assertThat(env.getValue()).isNotNull();
    }
}
