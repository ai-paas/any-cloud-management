package com.aipaas.anycloud.domain.addon.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * 카탈로그의 values 는 YAML 이고 agent 의 INSTALL_ADDON 은 JSON 만 받는다.
 *
 * <p>YAML 을 그대로 보내면 주석 한 줄에 {@code invalid character '#'} 로 거절된다. gpu-operator
 * 처럼 values 가 있는 addon 만 걸려, 다른 addon 이 설치되는 동안 GPU 만 조용히 빠진다.
 */
public final class AddonValues {

    private AddonValues() {}

    private static final ObjectMapper JSON = new ObjectMapper();

    public static String toJson(String valuesYaml) {
        if (valuesYaml == null || valuesYaml.isBlank()) {
            return valuesYaml;
        }
        String trimmed = valuesYaml.trim();
        if (trimmed.startsWith("{")) {
            return valuesYaml;
        }
        Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
        Object parsed = yaml.load(valuesYaml);
        if (!(parsed instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("addon values 가 매핑이 아닙니다");
        }
        try {
            return JSON.writeValueAsString(map);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException("addon values 를 JSON 으로 바꾸지 못했습니다", e);
        }
    }
}
