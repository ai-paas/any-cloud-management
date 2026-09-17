package io.aipaas.cluster.agent.observability.port;

import java.util.List;

/** 모니터링 대상 cluster 목록을 starter 에 제공하는 SPI. */
public interface ClusterCatalog {

    /** 모니터링 가능한 cluster 의 식별자 목록. */
    List<String> listClusterNames();
}
