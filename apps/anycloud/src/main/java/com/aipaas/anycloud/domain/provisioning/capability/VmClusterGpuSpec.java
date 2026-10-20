package com.aipaas.anycloud.domain.provisioning.capability;

/** 프로비저닝 요청이 GPU 노드를 선택한 클러스터인지 판정. */
public interface VmClusterGpuSpec {

    boolean requestedGpuNodes(String clusterName);
}
