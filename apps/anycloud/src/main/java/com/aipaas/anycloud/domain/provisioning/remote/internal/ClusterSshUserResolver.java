package com.aipaas.anycloud.domain.provisioning.remote.internal;

import com.aipaas.anycloud.domain.provisioning.VmClusterEntity;
import com.aipaas.anycloud.domain.provisioning.properties.PulumiProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 해당 클러스터를 생성한 스택에서 접속 사용자 조회.
 *
 * <p>기본 계정은 CSP 별 상이. Alibaba Ubuntu 이미지에는 ubuntu 계정이 없고 키가 root 에 등록.
 * 백엔드 전역 설정 단일 값으로 접속하면 해당 CSP 만 publickey 거절.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ClusterSshUserResolver {

    private final ObjectMapper objectMapper;
    private final PulumiProperties pulumiProperties;

    /** 스택 output 부재 시 전역 기본값 사용. sshUser 출력 도입 이전 생성 클러스터 존재. */
    public String resolve(VmClusterEntity cluster) {
        if (cluster == null || cluster.getRawOutputs() == null) {
            return pulumiProperties.getSshUser();
        }
        try {
            JsonNode user = objectMapper.readTree(cluster.getRawOutputs()).path("sshUser");
            return user.isTextual() && !user.asText().isBlank() ? user.asText() : pulumiProperties.getSshUser();
        } catch (Exception e) {
            log.debug("스택 output 에서 sshUser 를 읽지 못했다 cluster={}: {}", cluster.getClusterName(), e.toString());
            return pulumiProperties.getSshUser();
        }
    }

    /** 스택을 직접 읽은 호출자용. */
    public String resolve(Map<String, Object> outputs) {
        Object user = outputs == null ? null : outputs.get("sshUser");
        return user instanceof String text && !text.isBlank() ? text : pulumiProperties.getSshUser();
    }
}
