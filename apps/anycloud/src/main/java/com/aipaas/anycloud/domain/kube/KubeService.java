package com.aipaas.anycloud.domain.kube;

import com.fasterxml.jackson.databind.JsonNode;
import io.aipaas.cluster.agent.runtime.ResolvedResource;
import io.aipaas.cluster.agent.runtime.ResourceKindInfo;
import java.util.List;

/**
 * <pre>
 * ClassName : KubeService
 * Type : interface
 * Description : 쿠버네티스 관련된 함수를 정리한 인터페이스입니다. 이후 모든 day-2 op 는
 *               agent path 전용 — fabric8 fall-through 없음.
 * Related : KubeServiceImpl
 * </pre>
 */
public interface KubeService {

    JsonNode getResources(String clusterName, String namespace, String kind);

    JsonNode getResource(String clusterName, String namespace, String kind, String name);

    /**
     * Manifest 적용 (kubectl apply 와 동일 시맨틱 — server-side, create or update).
     * <p>
     * agent 의 {@code APPLY_MANIFEST} 가 in-cluster 에서 parse + apply.
     * 단일 / multi-doc YAML 모두 동일하게 처리.
     *
     * @param clusterName 대상 cluster
     * @param namespace   path 의 namespace. cluster-scoped kind 면 무시. manifest 의 metadata.namespace
     *                    가 지정돼 있으면 그 값을 우선 사용.
     * @param manifest    YAML 또는 JSON 둘 다 가능. agent 가 auto-detect.
     * @return 적용된 자원의 K8s 표현 (JsonNode). create 든 update 든 동일 형식.
     */
    JsonNode applyResource(String clusterName, String namespace, String manifest);

    /**
     * {@link #applyResource} 의 dry-run 변형.
     *
     * <p>{@code dryRun=true} → K8s API server 가 admission / validation 만 수행하고 etcd 에 persist
     * 하지 않음. frontend 의 저장 전 "검증" / "미리보기" 버튼 path 에 사용.
     *
     * @param dryRun true 면 server-side dry-run. false 면 일반 apply 와 동일.
     */
    JsonNode applyResource(String clusterName, String namespace, String manifest, boolean dryRun);

    /**
     * Bootstrap-only manifest apply — backend 가 ClusterEntity 의 kubeconfig (CA / clientCert /
     * clientKey) 로 직접 fabric8 KubernetesClient 만들어 server-side apply.
     *
     * <p>호출 시점: cluster 등록 직후 agent 가 아직 cluster 안에 없어 {@link #applyResource} 의
     * agent gRPC routing 이 "no active session" 으로 실패하는 chicken-and-egg 케이스. Day-2 ops
     * 는 여전히 {@link #applyResource} (agent-only). 본 메서드는 install-time 1회성 fallback.
     *
     * @param clusterName ClusterEntity.id — kubeconfig 조회용
     * @param namespace manifest 의 metadata.namespace 가 비어있을 때 fallback. cluster-scoped 리소스면 무시.
     * @param manifest YAML 또는 JSON. multi-doc YAML 도 지원.
     * @return 적용된 자원의 K8s 표현 (마지막 자원 기준).
     * @throws com.aipaas.anycloud.common.error.exception.CustomException kubeconfig 누락 / apply 실패 시.
     */
    JsonNode applyResourceViaBootstrap(String clusterName, String namespace, String manifest);

    boolean deleteResource(String clusterName, String namespace, String kind, String name);

    /**
     * K8s server-side pagination 으로 Pod 목록을 한 페이지씩 조회. 100+ pod 환경에서
     * 메모리/응답시간 안정화. continueToken 이 null 또는 빈 문자열이면 마지막 페이지.
     *
     * @param clusterName 클러스터 이름
     * @param namespace 네임스페이스 (null 이면 all-namespaces)
     * @param limit 한 페이지 최대 개수 (1..500)
     * @param continueToken 이전 응답의 continueToken (첫 호출은 null)
     * @param labelSelector 라벨 셀렉터(K8s 형식, 예: "app=nginx,tier=web") — null/빈 문자열이면 미적용
     */
    PagedKubeResourceResponse listPodsPaginated(
            String clusterName, String namespace, int limit, String continueToken, String labelSelector);

    /**
     * 임의 kind 의 server-side pagination. 지원 kind: pods, services, deployments, statefulsets,
     * daemonsets, replicasets, configmaps, secrets, persistentvolumeclaims, jobs, cronjobs.
     * 미지원 kind 면 IllegalArgumentException.
     */
    PagedKubeResourceResponse listResourcesPaginated(
            String clusterName, String namespace, String kind, int limit, String continueToken, String labelSelector);

    /**
     * fieldSelector 를 지원하는 listResourcesPaginated 변형. Event 같이 involvedObject.* 로 필터링이 필요한
     * 자원 조회에 사용. labelSelector 와 AND 조합.
     */
    PagedKubeResourceResponse listResourcesPaginated(
            String clusterName,
            String namespace,
            String kind,
            int limit,
            String continueToken,
            String labelSelector,
            String fieldSelector);

    /**
     * 특정 리소스에 연관된 K8s Event 목록 — fieldSelector {@code involvedObject.kind=<Kind>,involvedObject.name=<name>}
     * 로 events kind 를 paginated list.
     *
     * @param involvedKind 리소스 path 의 plural (예: "pods", "deployments"). K8s Kind 로 정규화하여 사용.
     * @param involvedName 리소스 이름.
     */
    PagedKubeResourceResponse listEventsFor(
            String clusterName, String namespace, String involvedKind, String involvedName);

    /**
     * 리소스 재시작.
     * <ul>
     *   <li>pods → delete (컨트롤러가 재생성) — Pod 가 standalone 이면 delete 만 되고 재생성되지 않음.</li>
     *   <li>deployments, statefulsets, daemonsets → spec.template.metadata.annotations 에
     *       {@code kubectl.kubernetes.io/restartedAt = <now>} 추가 후 apply (rollout restart).</li>
     *   <li>그 외 → {@link IllegalArgumentException}.</li>
     * </ul>
     */
    JsonNode restartResource(String clusterName, String namespace, String kind, String name);

    /**
     * replicas 변경. 지원 kind: deployments, replicasets, statefulsets. 그 외 {@link IllegalArgumentException}.
     * 동시 수정 race 는 인지하되 본 라운드는 get + modify + apply 패턴을 사용.
     */
    JsonNode scaleResource(String clusterName, String namespace, String kind, String name, int replicas);

    /**
     * Pod 의 container log snapshot (kubectl logs 등가).
     *
     * @param clusterName 대상 cluster
     * @param namespace pod namespace (null 또는 빈 문자열이면 default)
     * @param podName pod 이름
     * @param container 다중 container 일 때 지정 (null 이면 첫 container)
     * @param tailLines 마지막 N 줄만 반환 (1..10000, null 이면 default 100)
     * @param previous true 면 crash 직전 container 의 previous log (kubectl --previous)
     * @param sinceSeconds 지정 시 N 초 이내 로그만 (null 이면 미적용)
     * @return 로그 문자열. pod / container 가 존재하지 않으면 빈 문자열.
     */
    String getPodLogs(
            String clusterName,
            String namespace,
            String podName,
            String container,
            Integer tailLines,
            boolean previous,
            Integer sinceSeconds);

    /**
     * Cluster 의 discovery API 가 노출하는 모든 API resource (kind) enumerate. CRD 도 자동 포함.
     *
     * <p>UI 의 "resource kind picker" 채울 때 사용. cluster 마다 결과가 다름 (CRD 다양함). 응답은
     * group/plural 순으로 정렬.
     *
     * @return 정렬된 ResourceKindInfo list. cluster 가 unreachable 이면 {@code AGENT_UNAVAILABLE}.
     */
    List<ResourceKindInfo> listResourceKinds(String clusterName);

    /**
     * 사용자 입력 (단축이름 "pvc" / plural "pods" / Kind "PersistentVolumeClaim") 을 정규화.
     * type-ahead / form validation / 에러 보강에 사용.
     *
     * <p>resolve 실패 시 {@link io.aipaas.cluster.agent.runtime.UnsupportedKindException} —
     * fuzzy suggestion 포함. controller 가 404 + suggestions 로 매핑.
     *
     * @return resolve 된 ResolvedResource (plural/singular/kind/group/version/namespaced/shortNames)
     */
    ResolvedResource resolveResource(String clusterName, String input);
}
