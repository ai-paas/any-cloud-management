package io.aipaas.cluster.provisioning.program.yaml;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

/**
 * {@code Pulumi.yaml} 프로그램의 트리 표현.
 *
 * <p>문자열 조립 대신 Map, List 트리를 직렬화. user-data 처럼 개행과 따옴표가 혼재하는 값을
 * 문자열로 조립하면 따옴표와 들여쓰기를 수동 관리하게 되고 오류 발생.
 *
 * <p>{@link LinkedHashMap} 사용 목적은 진단. 순서가 고정되면 두 스택의 Pulumi.yaml 을 diff 로
 * 비교 가능.
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
        // 긴 user-data 가 임의 위치에서 줄바꿈되면 Pulumi 읽기 시 값 변형.
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
            // 미고정 시 캐시가 빈 환경에서 해당 시점 latest 수신.
            String version = pluginVersions.forType(type);
            if (version != null) {
                merged.putIfAbsent("version", version);
            }
            String downloadUrl = PluginVersions.downloadUrlForType(type);
            if (downloadUrl != null) {
                merged.putIfAbsent("pluginDownloadURL", downloadUrl);
            }
            if (!merged.isEmpty()) {
                entry.put("options", merged);
            }
            resources.put(name, entry);
            return this;
        }

        /**
         * 사전 컴파일된 플러그인이 없는 provider 선언. {@code terraform-provider} 베이스가
         * 런타임에 OpenTofu provider 를 로드. 이 선언만으로는 부족하며 {@code sdks/} 스키마가
         * 함께 있어야 타입 해석 가능.
         */
        public Builder pkg(String name, String source, String version, List<String> parameters) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("source", source);
            entry.put("version", version);
            entry.put("parameters", List.copyOf(parameters));
            packages.put(name, entry);
            return this;
        }

        /** provider function 호출 결과 등 리소스가 아닌 값. {@code ${name.field}} 로 참조. */
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
            // 빈 값이어도 항상 포함. resources 키 부재 시 CLI 가 프로그램으로 인식하지 않음.
            doc.put("resources", resources);
            if (!outputs.isEmpty()) {
                doc.put("outputs", outputs);
            }
            return new PulumiProgram(doc);
        }
    }
}
