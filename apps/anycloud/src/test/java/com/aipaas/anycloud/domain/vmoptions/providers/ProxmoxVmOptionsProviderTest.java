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
 * 노드 이름, datastore, 브리지는 호스트마다 다르다.
 *
 * <p>콘솔을 열어 베껴 적게 두면 오타가 프로비저닝 직전까지 드러나지 않는다. 특히 datastore 는
 * 종류를 잘못 고르면(블록에 이미지, 디렉터리에 디스크) PVE 가 거절한다.
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
        // 디렉터리 스토리지(local)에 디스크를 만들려 하면 PVE 가 거절한다.
        assertThat(options("providerSpec.datastoreId")).containsExactly("local-lvm", "NVME_1TB");
    }

    @Test
    void imageDatastoresOnlyListDirectoriesThatAcceptImport() throws Exception {
        // 블록 스토리지는 import content 를 받지 않아 이미지 다운로드가 실패한다.
        assertThat(options("providerSpec.imageDatastoreId")).containsExactly("local");
    }

    @Test
    void onlyBridgesAreOfferedAsNetworks() throws Exception {
        // 물리 인터페이스를 고르면 VM 이 붙지 못한다.
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
        // Proxmox 는 "코어-메모리MiB" 를 직접 받는다. 목록을 흉내 내면 없는 선택지를 고르게 된다.
        assertThat(provider().listSpecs("proxmox", null, false, 100)).isEmpty();
        assertThat(provider().listImages("proxmox", null, null, null, 100)).isEmpty();
    }

    @Test
    void anUnreachableHostLeavesTheFieldFree() throws Exception {
        // 목록을 못 준다고 생성을 막을 이유는 없다. 자유 입력으로 남는다.
        when(api.get(any(), any())).thenReturn(null);

        assertThat(new ProxmoxVmOptionsProvider(api).listConfigOptions("providerSpec.nodeName", "proxmox"))
                .isEmpty();
    }

    @Test
    void credentialKeysAreReadFromTheRegisteredCredential() throws Exception {
        // env fallback 으로 떨어지면 남의 호스트를 조회하거나 빈 목록이 된다.
        ProxmoxVmOptionsProvider p = provider();
        p.listConfigOptions("providerSpec.nodeName", "proxmox");

        org.mockito.ArgumentCaptor<Map<String, String>> env = org.mockito.ArgumentCaptor.forClass(Map.class);
        org.mockito.Mockito.verify(api, org.mockito.Mockito.atLeastOnce()).get(env.capture(), eq("/api2/json/nodes"));
        assertThat(env.getValue()).isNotNull();
    }
}
