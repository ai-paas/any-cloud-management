package io.aipaas.cluster.provisioning.program.yaml;

import java.util.LinkedHashMap;
import java.util.List;
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
        private final PluginVersions pluginVersions = PluginVersions.fromEnvironment();
        private final Map<String, Object> packages = new LinkedHashMap<>();
        private final Map<String, Object> variables = new LinkedHashMap<>();
        private final Map<String, Object> resources = new LinkedHashMap<>();
        private final Map<String, Object> outputs = new LinkedHashMap<>();

        private Builder(String projectName) {
            this.projectName = projectName;
        }

        public Builder resource(String name, String type, Map<String, Object> properties) {
            return resource(name, type, properties, Map.of());
        }

        public Builder resource(String name, String type, Map<String, Object> properties, Map<String, Object> options) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("type", type);
            entry.put("properties", new LinkedHashMap<>(properties));
            Map<String, Object> merged = new LinkedHashMap<>(options);
            // 고정하지 않으면 캐시가 빈 환경에서 그날의 latest 를 받는다.
            String version = pluginVersions.forType(type);
            if (version != null) {
                merged.putIfAbsent("version", version);
            }
            if (!merged.isEmpty()) {
                entry.put("options", merged);
            }
            resources.put(name, entry);
            return this;
        }

        /**
         * 사전 컴파일된 플러그인이 없는 provider 선언. {@code terraform-provider} 베이스가 런타임에
         * OpenTofu provider 를 붙인다. 이 선언만으로는 부족하고 {@code sdks/} 스키마가 옆에 있어야
         * 타입이 해석된다.
         */
        public Builder pkg(String name, String source, String version, List<String> parameters) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("source", source);
            entry.put("version", version);
            entry.put("parameters", List.copyOf(parameters));
            packages.put(name, entry);
            return this;
        }

        /** provider function 호출 결과처럼 리소스가 아닌 값. {@code ${name.field}} 로 참조한다. */
        public Builder variable(String name, Object value) {
            variables.put(name, value);
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
            if (!packages.isEmpty()) {
                doc.put("packages", packages);
            }
            if (!variables.isEmpty()) {
                doc.put("variables", variables);
            }
            // 비어도 항상 넣는다 — resources 키가 없으면 CLI 가 프로그램으로 인정하지 않는다.
            doc.put("resources", resources);
            if (!outputs.isEmpty()) {
                doc.put("outputs", outputs);
            }
            return new PulumiProgram(doc);
        }
    }
}
