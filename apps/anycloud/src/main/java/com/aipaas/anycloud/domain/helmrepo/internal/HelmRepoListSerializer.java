package com.aipaas.anycloud.domain.helmrepo.internal;

import com.aipaas.anycloud.domain.helmrepo.HelmRepoEntity;
import com.aipaas.anycloud.domain.helmrepo.HelmRepoRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 등록된 모든 helm repo 를 agent push 용 JSON array 로 직렬화.
 *
 * <p>Agent 의 reconciler 가 ConfigMap 의 {@code helm_repositories} key 의 JSON 을 parse 해 helm SDK
 * RepositoryFile 갱신. backend 의 DB 가 source-of-truth — repo CRUD 시점 또는 cluster ACTIVE 전환
 * 시점에 본 직렬화 결과를 모든 active cluster 에 push.
 *
 * <p>출력 형식:
 * <pre>
 * [
 *   {
 *     "name": "prometheus-community",
 *     "url": "https://prometheus-community.github.io/helm-charts",
 *     "username": "",
 *     "password": "",
 *     "ca_file": "",
 *     "insecure_skip_tls_verify": false
 *   },
 *   ...
 * ]
 * </pre>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HelmRepoListSerializer {

    private final HelmRepoRepository helmRepoRepository;
    private final ObjectMapper objectMapper;

    /**
     * 등록된 모든 helm repo 를 JSON array string 으로. 빈 list 면 {@code "[]"}. 직렬화 실패 시 fallback
     * 으로 빈 array 반환 + warn log — agent 가 빈 repo set 으로 reconcile (deregistration 효과).
     */
    public String serializeAll() {
        try {
            List<HelmRepoEntity> repos = helmRepoRepository.findAll();
            List<Map<String, Object>> items = new ArrayList<>(repos.size());
            for (HelmRepoEntity r : repos) {
                Map<String, Object> obj = new LinkedHashMap<>();
                obj.put("name", r.getName());
                obj.put("url", r.getUrl());
                obj.put("username", nz(r.getUsername()));
                obj.put("password", nz(r.getPassword()));
                obj.put("ca_file", nz(r.getCaFile()));
                obj.put(
                        "insecure_skip_tls_verify",
                        r.getInsecureSkipTlsVerify() != null && r.getInsecureSkipTlsVerify());
                items.add(obj);
            }
            return objectMapper.writeValueAsString(items);
        } catch (JsonProcessingException e) {
            log.warn("HelmRepo serialize failed — falling back to empty array: {}", e.getMessage());
            return "[]";
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
