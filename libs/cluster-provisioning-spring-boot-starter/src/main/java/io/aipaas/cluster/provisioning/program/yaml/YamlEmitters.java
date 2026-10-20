package io.aipaas.cluster.provisioning.program.yaml;

import io.aipaas.cluster.provisioning.program.ClusterSpec;
import io.aipaas.cluster.provisioning.program.ProviderName;
import java.util.List;

/** emitter 를 canonical provider 토큰으로 골라 실행하고 표준 출력까지 붙인다. */
public final class YamlEmitters {

    private static final List<ProviderYamlEmitter> EMITTERS = List.of(
            new OpenstackYamlEmitter(),
            new AwsYamlEmitter(),
            new GcpYamlEmitter(),
            new OciYamlEmitter(),
            new AlibabaYamlEmitter(),
            new ProxmoxYamlEmitter(),
            new IbmYamlEmitter());

    private YamlEmitters() {}

    /** YAML 경로 지원 provider 여부. 그 외는 inline 프로그램으로 실행. */
    public static boolean supports(String provider) {
        String canonical = ProviderName.canonical(provider);
        return EMITTERS.stream().anyMatch(emitter -> emitter.name().equals(canonical));
    }

    /** 등록된 provider 토큰. 계약 검사의 emitter 누락 방지를 위해 목록 노출. */
    public static List<String> supported() {
        return EMITTERS.stream().map(ProviderYamlEmitter::name).sorted().toList();
    }

    public static void emit(PulumiProgram.Builder builder, ClusterSpec spec) {
        String canonical = ProviderName.canonical(spec.provider());
        ProviderYamlEmitter emitter = EMITTERS.stream()
                .filter(candidate -> candidate.name().equals(canonical))
                .findFirst()
                .orElseThrow(() ->
                        new IllegalStateException("YAML emitter 가 없는 provider: " + canonical + " — inline 경로를 쓴다"));
        StandardOutputs.apply(builder, spec, emitter.emit(builder, spec));
    }
}
