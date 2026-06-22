package io.aipaas.cluster.provisioning.program.provisioner;

/** Control-plane (master) vs workload (worker) 구분. NodeSpec 의 필드. */
public enum InstanceRole {
    MASTER("master"),
    WORKER("worker");

    private final String token;

    InstanceRole(String token) {
        this.token = token;
    }

    /** 리소스/tag suffix 로 쓰일 안전 토큰. */
    public String token() {
        return token;
    }
}
