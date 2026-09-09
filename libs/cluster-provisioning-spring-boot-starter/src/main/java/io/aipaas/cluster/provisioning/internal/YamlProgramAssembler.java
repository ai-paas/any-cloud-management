package io.aipaas.cluster.provisioning.internal;

import io.aipaas.cluster.provisioning.api.ProvisioningRequest;
import io.aipaas.cluster.provisioning.program.ClusterSpec;
import io.aipaas.cluster.provisioning.program.Defaults;
import io.aipaas.cluster.provisioning.program.yaml.PulumiProgram;
import io.aipaas.cluster.provisioning.program.yaml.YamlEmitters;
import java.util.LinkedHashMap;
import java.util.Map;

/** ProvisioningRequest → Pulumi.yaml 프로그램. */
public final class YamlProgramAssembler {

    static final String PROJECT_NAME = "anycloud-k8s";

    private YamlProgramAssembler() {}

    public static PulumiProgram assemble(ProvisioningRequest request) {
        ClusterSpec spec = Defaults.applyProviderDefaults(ClusterSpec.from(stackConfig(request)));
        PulumiProgram.Builder builder = PulumiProgram.builder(PROJECT_NAME);
        YamlEmitters.emit(builder, spec);
        return builder.build();
    }

    /** destroy 용 — 리소스 정의 없이 스택만 선택하면 된다. */
    public static PulumiProgram emptyProgram() {
        return PulumiProgram.builder(PROJECT_NAME).build();
    }

    /**
     * {@code applyConfig} 가 스택에 쓰는 것과 같은 값을 모은다.
     *
     * <p>두 곳이 갈라지면 스택 config 와 YAML 프로그램이 다른 스펙을 보게 되고, 증상이 "설정을 바꿨는데
     * 반영이 안 된다" 로만 나타나 원인을 찾기 어렵다.
     */
    private static Map<String, String> stackConfig(ProvisioningRequest request) {
        Map<String, String> config = new LinkedHashMap<>(request.configOrEmpty());
        putIfPresent(config, "provider", request.getProvider());
        putIfPresent(config, "name", request.getClusterName());
        putIfPresent(config, "environment", request.getEnvironment());
        putIfPresent(config, "region", request.getRegion());
        return config;
    }

    private static void putIfPresent(Map<String, String> config, String key, String value) {
        if (value != null && !value.isBlank()) {
            config.put(key, value);
        }
    }
}
