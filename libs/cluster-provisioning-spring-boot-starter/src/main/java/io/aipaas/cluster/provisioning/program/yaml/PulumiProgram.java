package io.aipaas.cluster.provisioning.program.yaml;

import java.util.LinkedHashMap;
import java.util.Map;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

/**
 * {@code Pulumi.yaml} 프로그램의 트리 표현.
 *
 * <p>문자열을 조립하지 않고 Map/List 트리를 직렬화한다. user-data 처럼 개행과 따옴표가 섞인 값을
 * 문자열로 조립하면 따옴표와 들여쓰기를 사람이 관리하게 되고 반드시 깨진다.
 *
 * <p>{@link LinkedHashMap} 을 쓰는 이유는 진단이다. 순서가 안정되면 두 스택의 Pulumi.yaml 을
 * diff 해서 차이를 바로 볼 수 있다.
 */
public final class PulumiProgram {

    private final Map<String, Object> document;

    private PulumiProgram(Map<String, Object> document) {
        this.document = document;
    }

    public static Builder builder(String projectName) {
        return new Builder(projectName);
    }

    public String toYaml() {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        // 긴 user-data 가 임의 위치에서 접히면 Pulumi 가 읽을 때 값이 달라진다.
        options.setWidth(Integer.MAX_VALUE);
        options.setSplitLines(false);
        return new Yaml(options).dump(document);
    }

    public static final class Builder {

        private final String projectName;
        private final Map<String, Object> resources = new LinkedHashMap<>();
        private final Map<String, Object> outputs = new LinkedHashMap<>();

        private Builder(String projectName) {
            this.projectName = projectName;
        }

        public Builder resource(String name, String type, Map<String, Object> properties) {
            return resource(name, type, properties, Map.of());
        }

        public Builder resource(
                String name, String type, Map<String, Object> properties, Map<String, Object> options) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("type", type);
            entry.put("properties", new LinkedHashMap<>(properties));
            if (!options.isEmpty()) {
                entry.put("options", new LinkedHashMap<>(options));
            }
            resources.put(name, entry);
            return this;
        }

        public Builder output(String key, Object value) {
            outputs.put(key, value);
            return this;
        }

        public PulumiProgram build() {
            Map<String, Object> doc = new LinkedHashMap<>();
            doc.put("name", projectName);
            doc.put("runtime", "yaml");
            // 비어도 항상 넣는다 — resources 키가 없으면 CLI 가 프로그램으로 인정하지 않는다.
            doc.put("resources", resources);
            if (!outputs.isEmpty()) {
                doc.put("outputs", outputs);
            }
            return new PulumiProgram(doc);
        }
    }
}
