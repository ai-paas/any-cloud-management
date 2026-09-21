package com.aipaas.anycloud.domain.provisioning.capability;

/** 프로비저닝 요청이 GPU 노드를 고른 클러스터인지 묻는다. */
public interface VmClusterGpuSpec {

    boolean requestedGpuNodes(String clusterName);
}
