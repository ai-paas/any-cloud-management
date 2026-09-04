package io.aipaas.cluster.provisioning.program.yaml;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Pulumi YAML 의 표현식 생성 헬퍼.
 *
 * <p>YAML 에는 두 가지 표현식만 있다 — {@code ${...}} 보간과 {@code fn::} 접두 함수. 산술도
 * 비교도 분기도 없다. 그 계산은 전부 이 YAML 을 만드는 Java 가 미리 한다.
 */
public final class YamlRef {

    private YamlRef() {}

    /**
     * {@code ${resource}} — 리소스 자체 참조.
     *
     * <p>{@code dependsOn} 은 속성이 아니라 리소스를 받는다. {@code ${res.id}} 를 넘기면 Pulumi 가
     * "to must be a struct type, got pulumiyaml.lateboundResource" 로 panic 한다.
     */
    public static String resource(String resource) {
        return "${" + resource + "}";
    }

    /** {@code ${resource.property}} — 타입 SDK 의 {@code resource.property()} 등가. */
    public static String of(String resource, String property) {
        return "${" + resource + "." + property + "}";
    }

    /**
     * 보간을 문자열 안에 끼워 넣는다. {@code applyValue(ip -> "https://" + ip + ":6443")} 의 등가물.
     *
     * <p>{@code String.format} 을 쓰므로 template 의 리터럴 {@code %} 는 {@code %%} 로 적어야 한다.
     */
    public static String interpolate(String template, Object... args) {
        return String.format(template, args);
    }

    /**
     * 값을 JSON 문자열로 직렬화한다.
     *
     * <p>stack output 이 배열이나 객체면 Pulumi Java Automation SDK 의 {@code getStackOutputs}
     * 가 문자열을 기대해 gson 단계에서 죽는다. {@code WorkspaceStack.up} 이 내부에서 이를
     * 호출하므로 YAML 경로도 예외가 아니다.
     */
    public static Map<String, Object> toJson(Object value) {
        Map<String, Object> wrapped = new LinkedHashMap<>(1);
        wrapped.put("fn::toJSON", value);
        return wrapped;
    }

    /** {@code .asSecret()} 등가. 상태 파일과 stack output 양쪽에서 암호화된다. */
    public static Map<String, Object> secret(Object value) {
        Map<String, Object> wrapped = new LinkedHashMap<>(1);
        wrapped.put("fn::secret", value);
        return wrapped;
    }

    /**
     * provider function 호출. 타입 SDK 의 {@code XxxFunctions.getYyy(args)} 등가.
     *
     * @param returnField null 이면 객체 전체, 값이 있으면 그 필드 하나만 받는다
     */
    public static Map<String, Object> invoke(String function, Map<String, Object> arguments, String returnField) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("function", function);
        body.put("arguments", new LinkedHashMap<>(arguments));
        if (returnField != null && !returnField.isBlank()) {
            body.put("return", returnField);
        }
        Map<String, Object> wrapped = new LinkedHashMap<>(1);
        wrapped.put("fn::invoke", body);
        return wrapped;
    }
}
