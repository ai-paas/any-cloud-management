package io.aipaas.cluster.provisioning.program.provisioner;

import com.pulumi.Context;
import com.pulumi.core.Output;
import io.aipaas.cluster.provisioning.program.ClusterSpec;
import io.aipaas.cluster.provisioning.program.K8sConstants;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 모든 CSP provisioner 의 공통 lifecycle — TLS keypair 생성 흐름 + 표준 output schema 조립.
 * 서브클래스는 {@link #provisionResources(Context, ClusterSpec)} 에서 CSP-specific resource 만 생성.
 *
 * <p>표준 output keys (ProvisioningResultMapper 가 기대):
 * <ul>
 *   <li>provider, clusterName, masterVmSpec, workerVmSpec, osImage</li>
 *   <li>vpcId, masterInstanceId, masterPublicIp, masterPrivateIp, masterPublicDns</li>
 *   <li>apiServerUrl (secret), sshPrivateKeyPem (secret)</li>
 *   <li>masterSshCommand (secret), kubeconfigRemotePath, kubeconfigFetchCommand (secret)</li>
 *   <li>nodes (array)</li>
 *   <li>extras 에 담아 추가하는 CSP-specific (예: dbEndpoint)</li>
 * </ul>
 */
public abstract class AbstractKubeadmProvisioner implements ProviderProvisioner {

    @Override
    public final Map<String, Output<?>> provision(Context ctx, ClusterSpec spec) {
        ProvisionedCluster pc = provisionResources(ctx, spec);
        return assembleOutputs(spec, pc);
    }

    /** 서브클래스가 CSP-specific resource (네트워크, 인스턴스, optional DB 등) 를 만들고 결과 반환. */
    protected abstract ProvisionedCluster provisionResources(Context ctx, ClusterSpec spec);

    private Map<String, Output<?>> assembleOutputs(ClusterSpec spec, ProvisionedCluster pc) {
        Map<String, Output<?>> outputs = new LinkedHashMap<>();
        outputs.put("provider", Output.of(name()));
        outputs.put("clusterName", Output.of(spec.name()));
        outputs.put("masterVmSpec", Output.of(spec.masterInstanceType()));
        outputs.put("workerVmSpec", Output.of(spec.workerInstanceType()));
        outputs.put("osImage", Output.of(spec.osImageOrDefault()));
        outputs.put("vpcId", pc.vpcId());
        outputs.put("masterInstanceId", pc.master().instanceId());
        outputs.put("masterPublicIp", pc.master().publicIp());
        outputs.put("masterPrivateIp", pc.master().privateIp());
        outputs.put("masterPublicDns", pc.master().publicIp());
        outputs.put(
                "apiServerUrl",
                pc.master().publicIp().applyValue(ip -> "https://" + ip + ":" + K8sConstants.PORT_KUBE_API_SERVER));
        outputs.put("sshPrivateKeyPem", pc.sshKey().privateKeyPem().asSecret());
        outputs.put("kubeconfigRemotePath", Output.of("/etc/kubernetes/admin.conf"));
        outputs.put("masterSshCommand", sshCommand(spec, pc.master().publicIp()).asSecret());
        outputs.put("kubeconfigFetchCommand", kubeconfigFetchCommand(spec, pc.master().publicIp()).asSecret());
        outputs.put("nodes", nodesArray(spec, pc.master(), pc.workers()));
        outputs.putAll(pc.extras());
        return outputs;
    }

    private Output<String> sshCommand(ClusterSpec spec, Output<String> publicIp) {
        return publicIp.applyValue(ip ->
                "ssh -i ./secrets/" + spec.name() + ".pem " + spec.sshUser() + "@" + ip);
    }

    private Output<String> kubeconfigFetchCommand(ClusterSpec spec, Output<String> publicIp) {
        return publicIp.applyValue(ip ->
                "ssh -i ./secrets/" + spec.name() + ".pem " + spec.sshUser() + "@" + ip
                        + " \"sudo cat /etc/kubernetes/admin.conf\" > ./kubeconfig-" + spec.name());
    }

    /**
     * Pulumi Java SDK 1.30.0 의 일부 typed deserialize path (e.g. listTags 가 {@code Map<String,String>}
     * 강제) 가 stack output 의 array value 를 만나면 GSON 에서 "Expected a string but was BEGIN_ARRAY"
     * 로 깨진다. nodes 처럼 array 인 output 은 JSON string 으로 저장해 SDK 가 어떤 path 로 읽든 안전.
     * host 측 ProvisioningResultMapper 가 받은 후 parse. Jackson 미사용 — Pulumi SDK 가 동봉한 버전과
     * 충돌 회피, value 가 String 단일 type 이라 manual escape 로 충분.
     */
    private Output<String> nodesArray(
            ClusterSpec spec, InstanceOutput master, List<InstanceOutput> workers) {
        List<Output<Map<String, Object>>> entries = new ArrayList<>(1 + workers.size());
        entries.add(nodeEntry(spec, InstanceRole.MASTER, master));
        for (InstanceOutput w : workers) {
            entries.add(nodeEntry(spec, InstanceRole.WORKER, w));
        }
        return Output.all(entries).applyValue(AbstractKubeadmProvisioner::toJsonArray);
    }

    private static String toJsonArray(List<Map<String, Object>> nodes) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < nodes.size(); i++) {
            if (i > 0) sb.append(',');
            Map<String, Object> n = nodes.get(i);
            sb.append('{');
            int j = 0;
            for (Map.Entry<String, Object> e : n.entrySet()) {
                if (j++ > 0) sb.append(',');
                sb.append('"').append(jsonEscape(e.getKey())).append("\":");
                Object v = e.getValue();
                if (v == null) sb.append("null");
                else if (v instanceof Number || v instanceof Boolean) sb.append(v);
                else sb.append('"').append(jsonEscape(String.valueOf(v))).append('"');
            }
            sb.append('}');
        }
        return sb.append(']').toString();
    }

    private static String jsonEscape(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.toString();
    }

    private Output<Map<String, Object>> nodeEntry(ClusterSpec spec, InstanceRole role, InstanceOutput inst) {
        return Output.tuple(inst.instanceId(), inst.privateIp(), inst.publicIp()).applyValue(t -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("role", role.token());
            entry.put("instanceId", t.t1);
            entry.put("privateIp", t.t2);
            entry.put("publicIp", t.t3);
            entry.put("publicDns", t.t3);
            String ip = t.t3 == null || t.t3.isEmpty() ? t.t2 : t.t3;
            entry.put(
                    "ssh", "ssh -i ./secrets/" + spec.name() + ".pem " + spec.sshUser() + "@" + ip);
            return entry;
        });
    }
}
