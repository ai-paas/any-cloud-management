package io.aipaas.cluster.agent.backup.velero;

import java.util.List;
import java.util.Map;

/**
 * Velero Restore CR 생성 요청.
 *
 * @param name              Restore CR name
 * @param namespace         Velero ns. default "velero".
 * @param backupName        참조할 Backup CR name. 필수.
 * @param includedNamespaces 복구할 namespace (backup 의 부분집합). 비어있으면 backup 전체.
 * @param excludedNamespaces 제외할 namespace.
 * @param includedResources  복구할 resource kind. 비어있으면 모두.
 * @param namespaceMapping   backup 의 ns → restore target ns. 예: {"prod": "prod-restore"}.
 * @param restorePVs         PV 복구 여부. true (기본) — backup 이 snapshotVolumes 면 의미 있음.
 */
public record VeleroRestoreRequest(
        String name,
        String namespace,
        String backupName,
        List<String> includedNamespaces,
        List<String> excludedNamespaces,
        List<String> includedResources,
        Map<String, String> namespaceMapping,
        boolean restorePVs) {

    public static VeleroRestoreRequest fromBackup(String name, String backupName) {
        return new VeleroRestoreRequest(name, "velero", backupName, List.of(), List.of(), List.of(), Map.of(), true);
    }

    public static VeleroRestoreRequest withMapping(
            String name, String backupName, Map<String, String> namespaceMapping) {
        return new VeleroRestoreRequest(
                name, "velero", backupName, List.of(), List.of(), List.of(), namespaceMapping, true);
    }
}
