package io.aipaas.cluster.provisioning.program;

/**
 * VM cluster 외부 managed DB (RDS / Cloud SQL 등) spec. enabled=false 면 모든 다른 필드 무시.
 */
public record DatabaseSpec(
        boolean enabled,
        String name,
        String username,
        String password,
        String instanceClass,
        int allocatedStorageGb,
        boolean publiclyAccessible) {

    public static DatabaseSpec disabled() {
        return new DatabaseSpec(false, null, null, null, null, 0, false);
    }
}
