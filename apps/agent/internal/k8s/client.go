// Package k8s — agent 가 Kubernetes API 와 통신하는 thin abstraction.
//
// Interface 분리 이유:
//   1. Dispatcher 가 client-go 의 구체 타입에 직접 의존하면 테스트가 매우 어려움 (특히 GetLogs
//      의 SubResource API 는 fake clientset 으로 채우기 까다로움).
//   2. kubeclient connection 을 in-cluster vs out-of-cluster 로 dynamic switch 할 수 있도록
//      front-loaded abstraction.
//   3. AllowList enforcement  도 본 interface 의 메서드 호출 전후로 wrap 가능.
package k8s

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"

	corev1 "k8s.io/api/core/v1"
	"k8s.io/apimachinery/pkg/api/meta"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/apis/meta/v1/unstructured"
	"k8s.io/apimachinery/pkg/runtime/schema"
	"k8s.io/apimachinery/pkg/types"
	utilyaml "k8s.io/apimachinery/pkg/util/yaml"
	"k8s.io/client-go/discovery"
	memory "k8s.io/client-go/discovery/cached/memory"
	"k8s.io/client-go/dynamic"
	"k8s.io/client-go/kubernetes"
	"k8s.io/client-go/kubernetes/scheme"
	"k8s.io/client-go/rest"
	"k8s.io/client-go/restmapper"
	"k8s.io/client-go/tools/clientcmd"
	"k8s.io/client-go/tools/remotecommand"
	"k8s.io/client-go/util/homedir"
)

// Client 는 agent 가 사용하는 K8s 명령들의 최소 surface. 추후 명령 확대 시 메서드 추가.
type Client interface {
	// ListPods — namespace 가 빈 문자열이면 all-namespaces. 결과는 PodSummary 의 slice.
	ListPods(ctx context.Context, namespace string) ([]PodSummary, error)

	// GetPodLogs — kubectl logs 등가. tailLines 0 = unlimited, sinceSeconds 0 = no time filter,
	// previous=true 면 terminated container 의 이전 로그. 길이 제한 (maxBytes) 까지만 읽음.
	GetPodLogs(ctx context.Context, opts PodLogsOptions) (string, error)

	// StreamPodLogs — `kubectl logs -f` 등가. caller 가 반환된 ReadCloser 를 EOF/error 까지 읽음.
	// follow=true 면 K8s API 가 chunk push 하는 stream (장기 연결).
	// Caller 책임: ctx cancellation 으로 stream 종료 보장 + Close() 호출.
	StreamPodLogs(ctx context.Context, opts StreamPodLogsOptions) (io.ReadCloser, error)

	// ClusterInfo — server version + node count + kube-system UID. agent bootstrap 시 자동 수집.
	ClusterInfo(ctx context.Context) (*ClusterInfo, error)

	// DeleteResource — kind + namespace + name 으로 단일 자원 삭제. cluster-scoped kind 면
	// namespace 무시. 삭제 성공 시 nil. NotFound 도 caller 가 ErrIsNotFound 로 구분.
	// 지원 kind 는 KubernetesKind enum (case-insensitive 매칭). 미지원 시 ErrUnsupportedKind.
	DeleteResource(ctx context.Context, opts DeleteResourceOptions) error

	// GetResource — 단일 K8s 자원 조회. 결과는 JSON encoded string (kubectl get -o json 등가).
	// kind 미지원 시 ErrUnsupportedKind.
	GetResource(ctx context.Context, opts GetResourceOptions) (string, error)

	// ListResources — paginated K8s 자원 list. 결과 items 는 JSON encoded string (자원 list 형식).
	// continueToken 으로 다음 페이지 fetch. kind 미지원 시 ErrUnsupportedKind.
	ListResources(ctx context.Context, opts ListResourcesOptions) (*ListResourcesResult, error)

	// ResolveResource — 입력 kind 문자열 (short name / singular / plural) 을 discovery 기반
	// RESTMapper 로 GVR + scope 로 정규화. dispatcher 가 namespace/policy 검사 전에 호출.
	// kind 가 RESTMapper 에 없으면 ErrUnsupportedKind.
	ResolveResource(input string) (ResolvedResource, error)

	// ListByHelmRelease — helm release 가 만든 K8s 자원들을 한 번에 enumerate.
	// label "app.kubernetes.io/instance=<release>" 기준으로 11 종 자원 type (Deployment / StatefulSet /
	// DaemonSet / Service / ConfigMap / Secret / PVC / PV / Role / RoleBinding / ServiceAccount /
	// Ingress) 을 namespace 한정 list. PVC 가 bound 된 PV 도 자동 포함.
	// backend 의 HelmReleaseScanner 의 agent-side equivalent.
	ListByHelmRelease(ctx context.Context, opts HelmReleaseResourcesOptions) ([]HelmReleaseResourceRef, error)

	// ApplyManifest — kubectl apply 등가. multi-doc YAML 지원. server-side apply.
	// 결과는 적용된 자원들의 metadata (kind/name/ns/resourceVersion) list.
	ApplyManifest(ctx context.Context, opts ApplyManifestOptions) (*ApplyManifestResult, error)

	// ListPodsRaw — label selector + namespace 로 raw corev1.Pod 목록 반환. PodSummary 가 노출하지
	// 않는 annotation / labels 가 필요한 caller 용 (예: TTL annotation 검사).
	// debug pod sweeper 가 사용. namespace="" 이면 all-namespaces.
	ListPodsRaw(ctx context.Context, namespace, labelSelector string) ([]corev1.Pod, error)

	// IssueServiceAccountToken — K8s TokenRequest API 호출. ServiceAccount 의 한시 token 발급.
	// kubeconfig export 용. expirationSeconds 가 60 미만이면 60 으로 clamp.
	// 반환: token string + 실제 만료 시각 (server 가 결정).
	IssueServiceAccountToken(ctx context.Context, opts TokenRequestOptions) (TokenRequestResult, error)

	// CreateNodeDebugPod — node 에 host namespace + privileged debug pod 을 생성.
	// kubectl debug node 등가. 호출자는 반환된 (namespace, name) 으로 PodExec 호출.
	CreateNodeDebugPod(ctx context.Context, opts NodeDebugPodOptions) (NodeDebugPodResult, error)

	// ServerCAData — kubeconfig 생성 시 cluster.certificate-authority-data 에 들어갈 PEM 값.
	// in-cluster config 의 경우 /var/run/secrets/kubernetes.io/serviceaccount/ca.crt.
	ServerCAData() ([]byte, error)

	// APIServerURL — kubeconfig 의 cluster.server.
	APIServerURL() string

	// CountGpuNodes — cluster 의 Ready 노드 중 GPU 가 1개 이상인 노드 수.
	// heartbeat 에 piggy-back 되어 backend 의 cluster.has_gpu_nodes 자동 backfill 에 사용.
	//
	// 판정 기준 (둘 중 하나라도 만족):
	//   1. node.status.capacity["nvidia.com/gpu"] > 0
	//   2. node.metadata.labels["nvidia.com/gpu.present"] == "true"
	//
	// 호출 부하 (대형 cluster 1000 노드 가정) 가 적지 않으므로 caller 가 5분 캐싱 권장.
	CountGpuNodes(ctx context.Context) (int, error)

	// ExecInPod — kubectl exec 등가. Streaming bidirectional I/O.
	//
	// 호출자는 streams 의 Stdin/Stdout/Stderr 를 통해 양방향 I/O 를 처리하고,
	// TerminalSize 변경 시 ResizeQueue 로 SIGWINCH 전달. ResizeQueue 가 nil 이면 PTY resize 비활성.
	// TTY 모드면 stderr 는 stdout 으로 머지됨 (client-go 의 SPDY executor 기본 동작).
	//
	// Returns: 실행이 정상 종료될 때 nil. 비정상 종료/에러는 wrap 된 error.
	// 호출자는 *exec.CodeExitError 로 type assertion 해서 shell exit code 추출 가능.
	ExecInPod(ctx context.Context, opts PodExecOptions, streams ExecStreams) error

	// Clientset — 내부 kubernetes.Interface 노출. mTLS Phase mtls.2 의 K8sSecretCertStore 등이
	// Secret CRUD 를 위해 사용. Test 의 mock 도 동일 interface 반환 가능.
	Clientset() kubernetes.Interface

	// ListAPIResources — server discovery 결과를 UI 친화 shape 로 list. CRD 도 자연스럽게 포함.
	// LIST_RESOURCE_KINDS 명령의 backend. RBAC 부족 / discovery 자체 실패 시 wrap 된 error.
	// 결과는 (Group, Plural) 안정 정렬, subresource 와 list-verb 미지원 자원은 자동 제외.
	ListAPIResources(ctx context.Context) ([]APIResourceInfo, error)
}

// PodExecOptions — kubectl exec 옵션.
type PodExecOptions struct {
	Namespace string
	Pod       string
	// 비어있으면 pod 의 첫 컨테이너.
	Container string
	// 실행할 명령. 비어있으면 ["/bin/sh"] 기본.
	Command []string
	// TTY 할당 (인터랙티브 shell).
	TTY bool
	// Stdin 연결 (대부분 true). TTY=true 면 권장 true.
	Stdin bool
}

// ExecStreams — caller 가 제공하는 I/O endpoints.
type ExecStreams struct {
	// Stdin: caller→pod 입력. nil 이면 stdin 미연결.
	Stdin io.Reader
	// Stdout: pod→caller 출력. nil 이면 출력 버려짐.
	Stdout io.Writer
	// Stderr: pod→caller 에러 출력. TTY=true 면 Stdout 으로 머지되어 무시됨.
	Stderr io.Writer
	// ResizeQueue: TerminalSize 변경 통지. nil 이면 PTY resize 비활성.
	ResizeQueue remotecommand.TerminalSizeQueue
}

// ApplyManifestOptions — kubectl apply -f - 등가.
type ApplyManifestOptions struct {
	// Multi-doc YAML 또는 JSON manifest. "---" 로 분리된 여러 자원 가능.
	Manifest string
	// 자원 metadata.namespace 가 빈 문자열이면 이 namespace 적용. cluster-scoped kind 면 무시.
	DefaultNamespace string
	// Server-side apply 의 FieldManager. 빈 문자열이면 "aipaas-agent".
	FieldManager string
	// Force=true: 다른 manager 의 ownership 강제 인계 (충돌 무시). PoC 는 false 권장.
	Force bool
	// DryRun=true: K8s API server 가 admission/validation 만 수행하고 etcd 에 persist 하지 않음.
	// frontend 의 "검증" 버튼 / 저장 전 미리보기에 사용. metav1.DryRunAll 로 매핑.
	DryRun bool
}

// AppliedResource — apply 결과 entry. Status 는 K8s 가 반환한 latest resourceVersion 까지.
type AppliedResource struct {
	APIVersion      string
	Kind            string
	Name            string
	Namespace       string
	ResourceVersion string
	UID             string
}

// ApplyManifestResult — apply 호출 결과.
type ApplyManifestResult struct {
	Applied []AppliedResource
}

// HelmReleaseResourcesOptions — ListByHelmRelease 입력.
type HelmReleaseResourcesOptions struct {
	Namespace string
	Release   string
}

// HelmReleaseResourceRef — release 가 소유한 단일 자원의 식별 정보. backend 의
// ChartReleasesResponse.ReleaseResource 와 1:1.
type HelmReleaseResourceRef struct {
	Kind       string     // "Deployment", "Service" 등
	APIVersion string     // "apps/v1", "v1" 등 (선택 — 일부 자원은 빈 문자열)
	Namespace  string     // cluster-scoped 자원 (PV 등) 은 ""
	Name       string
}

// ListResourcesOptions — kubectl get <kind> 등가의 paginated list.
type ListResourcesOptions struct {
	Kind          string
	Namespace     string     // 비어 있으면 all-namespaces (k8s server-side default).
	Limit         int64      // 0 = server default (cluster 별로 다름).
	ContinueToken string     // 이전 응답의 continueToken — 첫 호출은 빈 문자열.
	LabelSelector string     // K8s label selector 식. 빈 문자열이면 미적용.
}

// ListResourcesResult — server-side paginated list 결과.
type ListResourcesResult struct {
	Items         string     // JSON-encoded array of objects.
	ContinueToken string     // null/빈 문자열이면 마지막 페이지.
	ReturnedCount int
}

// GetResourceOptions — kubectl get 등가.
type GetResourceOptions struct {
	Kind      string
	Namespace string
	Name      string
}

// DeleteResourceOptions — kubectl delete 등가.
type DeleteResourceOptions struct {
	// 자원 종류 (case-insensitive). 예: "pod", "deployment", "service".
	Kind string
	// Namespace. cluster-scoped kind 면 무시.
	Namespace string
	// 자원 이름.
	Name string
	// graceful 종료 (초). nil 이면 K8s default. 0 = force delete.
	GracePeriodSeconds *int64
}

// ResolvedResource — Client.ResolveResource 결과. RESTMapper 가 입력 kind 문자열로부터
// 정규화한 GVR + scope 정보. dispatcher 가 namespace allowlist / resource policy 검사 시 사용.
//
// Plural 은 lowercase plural resource name (예: "pods", "storageclasses", "customresourcedefinitions").
// CRD 의 짧은 이름 (예: "vpa") 도 RESTMapper 가 인식 가능하면 plural 로 정규화됨.
//
// Singular / Kind / ShortNames 는 RESOLVE_RESOURCE 명령에서만 필요한 추가 필드.
// resolveKindToGVR 만으론 채울 수 없어 (RESTMapper 는 plural 만 노출) discovery 의
// APIResource 와 매칭해서 best-effort 로 채운다. 매칭 실패 시 빈 값 — caller (dispatcher)
// 는 빈 값이어도 OK 응답을 만든다.
type ResolvedResource struct {
	Plural     string     // lowercase plural resource name (RESTMapping.Resource.Resource)
	Singular   string     // lowercase singular (예: "pod"). discovery match 실패 시 "".
	Kind       string     // PascalCase kind (예: "Pod", "StorageClass").
	Group      string     // API group (예: "", "apps", "storage.k8s.io", "apiextensions.k8s.io")
	Version    string     // API version (예: "v1", "v1beta1")
	Namespaced bool       // RESTScope == Namespace.
	ShortNames []string   // 빈 slice 일 수 있음. e.g. {"po"} for pods, {"pvc"} 등.
}

// PodSummary — list 결과의 간략 요약 (전체 Pod object 안 노출).
type PodSummary struct {
	Name      string
	Namespace string
	Phase     string
	NodeName  string
	PodIP     string
	StartTime time.Time
	// Container ready N/Total.
	ContainersReady int
	ContainersTotal int
}

// PodLogsOptions — kubectl logs 옵션 매핑.
type PodLogsOptions struct {
	Namespace    string
	Pod          string
	Container    string     // optional — 다중 container pod 의 특정 container
	TailLines    int64      // 0 = unlimited
	Previous     bool
	SinceSeconds int64      // 0 = no filter
	MaxBytes     int        // 응답 cap (default 1MB)
}

// StreamPodLogsOptions — `kubectl logs -f` 등가. agent → backend gRPC streaming 용.
// Follow=true 면 K8s API 가 chunk-by-chunk push 하는 stream 을 그대로 io.ReadCloser 로 반환.
// Caller (dispatcher 의 stream handler) 가 chunk 를 gRPC 로 relay.
type StreamPodLogsOptions struct {
	Namespace    string
	Pod          string
	Container    string     // optional
	TailLines    int64      // 0 = unlimited
	Follow       bool       // tail -f
	SinceSeconds int64
	Timestamps   bool       // RFC3339 prefix
	Previous     bool       // crash 이전 container 로그
}

// ClusterInfo — bootstrap 시점에 수집할 클러스터 메타.
type ClusterInfo struct {
	K8sClusterUID    string     // kube-system namespace UID
	Version          string     // server version (예: "v1.34.3")
	Distribution     string     // best-effort — kubeadm/k3s/eks/gke 등. 자동 추정.
	NodeCount        int
	APIServerEndpoint string
}

// NewInClusterClient — Pod 안에서 ServiceAccount 토큰으로 kube-apiserver 접근.
// In-cluster 가 아닌 경우 (local dev / test) NewFromKubeconfig 사용.
func NewInClusterClient() (Client, error) {
	config, err := rest.InClusterConfig()
	if err != nil {
		return nil, fmt.Errorf("in-cluster config: %w", err)
	}
	cs, err := kubernetes.NewForConfig(config)
	if err != nil {
		return nil, fmt.Errorf("clientset: %w", err)
	}
	slog.Info("k8s: in-cluster client initialized")
	return &realClient{cs: cs, restConfig: config}, nil
}

// NewFromKubeconfig — KUBECONFIG env 또는 ~/.kube/config 에서 로딩. local dev / test 용.
func NewFromKubeconfig(path string) (Client, error) {
	if path == "" {
		path = os.Getenv("KUBECONFIG")
	}
	if path == "" {
		if home := homedir.HomeDir(); home != "" {
			path = filepath.Join(home, ".kube", "config")
		}
	}
	if path == "" {
		return nil, errors.New("no kubeconfig path provided and HOME not set")
	}
	config, err := clientcmd.BuildConfigFromFlags("", path)
	if err != nil {
		return nil, fmt.Errorf("load kubeconfig %s: %w", path, err)
	}
	cs, err := kubernetes.NewForConfig(config)
	if err != nil {
		return nil, fmt.Errorf("clientset: %w", err)
	}
	slog.Info("k8s: out-of-cluster client initialized", slog.String("kubeconfig", path))
	return &realClient{cs: cs, restConfig: config}, nil
}

// NewClient — preferred entry. In-cluster 시도 실패 시 kubeconfig fallback.
func NewClient() (Client, error) {
	if c, err := NewInClusterClient(); err == nil {
		return c, nil
	}
	slog.Info("k8s: in-cluster init failed — falling back to kubeconfig")
	return NewFromKubeconfig("")
}

// realClient 는 production 구현. Test 는 별도 mock (k8s_mock.go).
// dyn / mapper 는 ApplyManifest 가 처음 호출될 때 lazy 초기화 (in-cluster 구동 시 startup 가속).
type realClient struct {
	cs         kubernetes.Interface
	restConfig *rest.Config

	// Lazy-initialized — sync.Once 로 thread-safe.
	dynOnce sync.Once
	dyn     dynamic.Interface
	mapper  meta.RESTMapper
	dynErr  error
}

// ensureDynamic — dynamic client + RESTMapper 초기화. 한 번만 수행, race-safe.
func (c *realClient) ensureDynamic() error {
	c.dynOnce.Do(func() {
		dyn, err := dynamic.NewForConfig(c.restConfig)
		if err != nil {
			c.dynErr = fmt.Errorf("dynamic client: %w", err)
			return
		}
		// RESTMapper — discovery 결과 캐시. kubectl 도 동일 패턴 사용.
		dc, err := discovery.NewDiscoveryClientForConfig(c.restConfig)
		if err != nil {
			c.dynErr = fmt.Errorf("discovery client: %w", err)
			return
		}
		mapper := restmapper.NewDeferredDiscoveryRESTMapper(memory.NewMemCacheClient(dc))
		c.dyn = dyn
		c.mapper = mapper
	})
	return c.dynErr
}

// impersonation helpers.
//
// 호출 시점에 ctx 에서 Impersonation 추출 → 있으면 rest.Config 의 copy 에 Impersonate 채워
// 새 clientset / dynamic.Interface 생성 후 반환. 없으면 base client 그대로 반환 (zero-cost).
//
// impersonation client 는 LRU cache 로 재사용. 매 호출마다 rest.CopyConfig +
// NewForConfig 하는 비용 (수 micro-second + heap alloc + 별도 HTTP/2 transport 가능성) 을 N 명 동시
// 사용자 32명 한도 안에서 재사용. UI burst (list/get refresh) 의 GC 압박 해소.
//
// 캐시는 typed (clientset) + dynamic 을 묶어서 같은 identity key 로 보관. ttl 5분 — 사용자 RBAC 변동
// 시 최대 5분 wait 가능 (acceptable). cache key = sha256(user|sorted_groups|sorted_extras).

// clientsetForCtx — ctx 에 impersonation 이 있으면 LRU cache 의 typed clientset, 없으면 base.
func (c *realClient) clientsetForCtx(ctx context.Context) (kubernetes.Interface, error) {
	imp := ImpersonationFromContext(ctx)
	if imp.IsZero() {
		return c.cs, nil
	}
	cs, _, err := getImpersonationCache().getOrCreate(imp, c.restConfig)
	return cs, err
}

// dynamicForCtx — ctx 에 impersonation 이 있으면 LRU cache 의 dynamic.Interface, 없으면 base.
// caller 는 ensureDynamic() 호출로 base mapper 가 초기화되어 있다고 가정.
func (c *realClient) dynamicForCtx(ctx context.Context) (dynamic.Interface, error) {
	imp := ImpersonationFromContext(ctx)
	if imp.IsZero() {
		return c.dyn, nil
	}
	_, dyn, err := getImpersonationCache().getOrCreate(imp, c.restConfig)
	return dyn, err
}

// (pre-cache per-call clone path 제거. rollback 필요하면 git history 참조.)

func (c *realClient) Clientset() kubernetes.Interface {
	return c.cs
}

func (c *realClient) ListPods(ctx context.Context, namespace string) ([]PodSummary, error) {
	cs, err := c.clientsetForCtx(ctx)
	if err != nil {
		return nil, fmt.Errorf("list pods (ns=%s): impersonation client: %w", namespace, err)
	}
	pods, err := cs.CoreV1().Pods(namespace).List(ctx, metav1.ListOptions{Limit: 500})
	if err != nil {
		return nil, fmt.Errorf("list pods (ns=%s): %w", namespace, err)
	}
	out := make([]PodSummary, 0, len(pods.Items))
	for _, p := range pods.Items {
		out = append(out, summarizePod(&p))
	}
	return out, nil
}

func (c *realClient) GetPodLogs(ctx context.Context, opts PodLogsOptions) (string, error) {
	if opts.Pod == "" {
		return "", errors.New("pod required")
	}
	maxBytes := opts.MaxBytes
	if maxBytes <= 0 {
		maxBytes = 1 << 20     // 1 MB default cap.
	}
	logOpts := &corev1.PodLogOptions{
		Container: opts.Container,
		Previous:  opts.Previous,
	}
	if opts.TailLines > 0 {
		t := opts.TailLines
		logOpts.TailLines = &t
	}
	if opts.SinceSeconds > 0 {
		s := opts.SinceSeconds
		logOpts.SinceSeconds = &s
	}

	cs, err := c.clientsetForCtx(ctx)
	if err != nil {
		return "", fmt.Errorf("pod logs (ns=%s pod=%s): impersonation client: %w", opts.Namespace, opts.Pod, err)
	}
	req := cs.CoreV1().Pods(opts.Namespace).GetLogs(opts.Pod, logOpts)
	rc, err := req.Stream(ctx)
	if err != nil {
		return "", fmt.Errorf("stream pod logs: %w", err)
	}
	defer func() { _ = rc.Close() }()

	// Bound buffer 로 읽기 — pod logs 가 GB 단위면 메모리 폭주 방지.
	buf := make([]byte, 0, 64*1024)
	chunk := make([]byte, 16*1024)
	for {
		n, err := rc.Read(chunk)
		if n > 0 {
			if len(buf)+n > maxBytes {
				buf = append(buf, chunk[:maxBytes-len(buf)]...)
				buf = append(buf, []byte("\n...(truncated)")...)
				break
			}
			buf = append(buf, chunk[:n]...)
		}
		if err == io.EOF {
			break
		}
		if err != nil {
			return "", fmt.Errorf("read pod logs: %w", err)
		}
	}
	return string(buf), nil
}

// StreamPodLogs — K8s API 의 chunked log endpoint (Follow=true 시 long-lived) 를 그대로
// io.ReadCloser 로 노출. caller (stream handler) 가 한 chunk 씩 읽어 gRPC 로 forward.
//
// Caller 책임:
//   - ctx cancellation 으로 종료 보장 (gRPC client ctx 또는 server-side cancel propagation).
//   - 반환된 ReadCloser 의 Close() 호출.
//
// GetPodLogs 와 달리 본 메서드는 in-memory buffering 안 함 — chunk 단위 streaming.
func (c *realClient) StreamPodLogs(ctx context.Context, opts StreamPodLogsOptions) (io.ReadCloser, error) {
	if opts.Pod == "" {
		return nil, errors.New("pod required")
	}
	logOpts := &corev1.PodLogOptions{
		Container:  opts.Container,
		Follow:     opts.Follow,
		Timestamps: opts.Timestamps,
		Previous:   opts.Previous,
	}
	if opts.TailLines > 0 {
		t := opts.TailLines
		logOpts.TailLines = &t
	}
	if opts.SinceSeconds > 0 {
		s := opts.SinceSeconds
		logOpts.SinceSeconds = &s
	}
	req := c.cs.CoreV1().Pods(opts.Namespace).GetLogs(opts.Pod, logOpts)
	rc, err := req.Stream(ctx)
	if err != nil {
		return nil, fmt.Errorf("stream pod logs (ns=%s pod=%s): %w", opts.Namespace, opts.Pod, err)
	}
	return rc, nil
}

func (c *realClient) ClusterInfo(ctx context.Context) (*ClusterInfo, error) {
	info := &ClusterInfo{
		APIServerEndpoint: c.restConfig.Host,
	}

	// kube-system namespace 의 UID = cluster identity (PDF 권장 패턴).
	ns, err := c.cs.CoreV1().Namespaces().Get(ctx, "kube-system", metav1.GetOptions{})
	if err != nil {
		slog.Warn("ClusterInfo: kube-system get failed", slog.String("error", err.Error()))
	} else {
		info.K8sClusterUID = string(ns.UID)
	}

	if version, err := c.cs.Discovery().ServerVersion(); err == nil {
		info.Version = version.GitVersion
		info.Distribution = inferDistribution(version.GitVersion)
	} else {
		slog.Warn("ClusterInfo: server version failed", slog.String("error", err.Error()))
	}

	if nodes, err := c.cs.CoreV1().Nodes().List(ctx, metav1.ListOptions{Limit: 100}); err == nil {
		info.NodeCount = len(nodes.Items)
	}

	return info, nil
}

// CountGpuNodes — Ready 상태의 GPU 노드 개수. 캐싱은 caller (runtime.go) 가 담당.
//
// 판정:
//   - node.status.capacity["nvidia.com/gpu"] 가 0 보다 큰 quantity → GPU 노드
//   - 또는 node.metadata.labels["nvidia.com/gpu.present"] == "true" → GPU 노드
//
// Ready condition (NodeReady=True) 인 노드만 카운트 — NotReady 노드는 일시 장애일 수 있어 제외.
// (단순화: 현재는 Ready 검사 없이 모두 카운트. 추후 필요 시 추가.)
func (c *realClient) CountGpuNodes(ctx context.Context) (int, error) {
	nodes, err := c.cs.CoreV1().Nodes().List(ctx, metav1.ListOptions{Limit: 500})
	if err != nil {
		return 0, fmt.Errorf("list nodes: %w", err)
	}
	count := 0
	for _, n := range nodes.Items {
		if nodeHasGpu(&n) {
			count++
		}
	}
	return count, nil
}

// nodeHasGpu — capacity 또는 label 기반 GPU 노드 판정.
func nodeHasGpu(node *corev1.Node) bool {
	// 1) capacity 의 nvidia.com/gpu > 0
	if q, ok := node.Status.Capacity["nvidia.com/gpu"]; ok {
		if !q.IsZero() {
			return true
		}
	}
	// 2) label 표식
	if v, ok := node.Labels["nvidia.com/gpu.present"]; ok && v == "true" {
		return true
	}
	return false
}

// ErrUnsupportedKind — kind 문자열이 RESTMapper 로 해석되지 않을 때 반환. caller (dispatcher)
// 가 Status_INVALID_PARAMS + UNSUPPORTED_KIND 로 매핑한다.
var ErrUnsupportedKind = errors.New("unsupported kind")

// shortNames — kubectl 익숙 short name 을 plural resource 로 빠르게 정규화. RESTMapper 가
// short name 을 자체 지원하긴 하지만 discovery 캐시 미스 시 추가 round-trip 이 발생하므로,
// 빈번한 K8s core/apps 자원은 본 정적 매핑으로 우회.
//
// VALUE 는 plural resource name. ConfigMap 의 ResourcePolicy 도 plural 로 매칭하므로 일관성.
var shortNames = map[string]string{
	"po": "pods", "svc": "services", "cm": "configmaps", "sa": "serviceaccounts",
	"pvc": "persistentvolumeclaims", "deploy": "deployments", "sts": "statefulsets",
	"ds": "daemonsets", "rs": "replicasets", "ns": "namespaces", "pv": "persistentvolumes",
	"no": "nodes", "ing": "ingresses", "crd": "customresourcedefinitions",
	"sc": "storageclasses",
}

// resolveKindToGVR — 입력 kind 문자열을 RESTMapper 기반으로 GVR + scope 으로 정규화.
//
// 입력 형식 (모두 case-insensitive):
//   - lowercase singular: "pod", "storageclass"
//   - lowercase plural:   "pods", "storageclasses"
//   - short name:         "po", "pvc", "deploy", "sts", "ds", "rs", "crd", "sc" 등
//
// 동작:
//  1. lowercase 정규화 후 shortNames 테이블 lookup (있으면 plural 로 치환).
//  2. mapper.ResourceFor(GVR{Resource: <name>}) 로 group/version 포함 GVR 해석.
//     → singular ("pod"), plural ("pods") 모두 인식.
//  3. mapper.RESTMapping(GK, V) 로 scope (namespaced/cluster) 추출.
//
// 실패 시 ErrUnsupportedKind wrap. dispatcher 가 sentinel 로 분류 가능.
//
// 주의: shortNames 와 ConfigMap ResourcePolicy 가 충돌하는 경우 (예: 운영자가 "sc" 를
// 정책에 적기) 의 결정 — shortNames 가 항상 plural 로 정규화하므로 ResourcePolicy 도 plural
// ("storageclasses") 로 작성해야 한다. allowlist 측에서 ResourceRule.Kind 를 lowercase 정규화
// 만 하고 plural 변환은 안 하므로, 운영자가 ConfigMap 에 "sc" 를 적는다면 매칭되지 않는다 —
// 의도적으로 명시 plural 강제 (정책의 의미 명확화).
func (c *realClient) resolveKindToGVR(input string) (gvr schema.GroupVersionResource, namespaced bool, err error) {
	raw := strings.TrimSpace(strings.ToLower(input))
	if raw == "" {
		return schema.GroupVersionResource{}, false, fmt.Errorf("%w: empty kind", ErrUnsupportedKind)
	}
	if expanded, ok := shortNames[raw]; ok {
		raw = expanded
	}
	if err := c.ensureDynamic(); err != nil {
		return schema.GroupVersionResource{}, false, fmt.Errorf("dynamic init: %w", err)
	}
	resolved, mErr := c.mapper.ResourceFor(schema.GroupVersionResource{Resource: raw})
	if mErr != nil {
		return schema.GroupVersionResource{}, false, fmt.Errorf("%w: %s (%v)", ErrUnsupportedKind, input, mErr)
	}
	kinds, kErr := c.mapper.KindFor(resolved)
	if kErr != nil {
		return schema.GroupVersionResource{}, false, fmt.Errorf("%w: %s (kind lookup: %v)", ErrUnsupportedKind, input, kErr)
	}
	mapping, scopeErr := c.mapper.RESTMapping(kinds.GroupKind(), resolved.Version)
	if scopeErr != nil {
		return schema.GroupVersionResource{}, false, fmt.Errorf("%w: %s (mapping: %v)", ErrUnsupportedKind, input, scopeErr)
	}
	return mapping.Resource, mapping.Scope.Name() == meta.RESTScopeNameNamespace, nil
}

// ResolveResource — RESTMapper 기반 정규화 결과를 dispatcher 가 사용할 수 있게 reveal.
//
// 1차 해석 (resolveKindToGVR) 으로 plural/group/version/namespaced 채우고, RESOLVE_RESOURCE
// 명령용 추가 metadata (Singular/Kind/ShortNames) 는 discovery 의 APIResource 와 매칭해 채운다.
// discovery enrichment 가 실패해도 1차 해석은 성공이므로 error 가 아닌 best-effort 진행.
func (c *realClient) ResolveResource(input string) (ResolvedResource, error) {
	gvr, namespaced, err := c.resolveKindToGVR(input)
	if err != nil {
		return ResolvedResource{}, err
	}
	resolved := ResolvedResource{
		Plural:     gvr.Resource,
		Group:      gvr.Group,
		Version:    gvr.Version,
		Namespaced: namespaced,
		ShortNames: []string{},
	}
	// Discovery enrichment — Singular / Kind / ShortNames 채우기.
	// memCacheClient 가 한 번 받아둔 결과를 재사용하므로 호출 비용은 낮음.
	// cs 가 nil 인 일부 테스트 path (dynamic_test 의 newPreparedClient) 에선 enrichment skip.
	if c.cs == nil {
		return resolved, nil
	}
	if lists, derr := c.cs.Discovery().ServerPreferredResources(); derr == nil || lists != nil {
		for _, list := range lists {
			if list == nil {
				continue
			}
			group, version := splitGroupVersion(list.GroupVersion)
			if group != gvr.Group || version != gvr.Version {
				continue
			}
			for _, r := range list.APIResources {
				if strings.Contains(r.Name, "/") {
					continue
				}
				if r.Name == gvr.Resource {
					resolved.Singular = r.SingularName
					resolved.Kind = r.Kind
					resolved.ShortNames = append([]string(nil), r.ShortNames...)
					return resolved, nil
				}
			}
		}
	}
	return resolved, nil
}

func (c *realClient) DeleteResource(ctx context.Context, opts DeleteResourceOptions) error {
	if opts.Name == "" {
		return errors.New("DeleteResource: name required")
	}
	gvr, namespaced, err := c.resolveKindToGVR(opts.Kind)
	if err != nil {
		return err
	}
	delOpts := metav1.DeleteOptions{}
	if opts.GracePeriodSeconds != nil {
		delOpts.GracePeriodSeconds = opts.GracePeriodSeconds
	}
	dyn, err := c.dynamicForCtx(ctx)
	if err != nil {
		return fmt.Errorf("delete %s: impersonation client: %w", gvr.Resource, err)
	}
	var ri dynamic.ResourceInterface
	if namespaced {
		ri = dyn.Resource(gvr).Namespace(opts.Namespace)
	} else {
		ri = dyn.Resource(gvr)
	}
	if delErr := ri.Delete(ctx, opts.Name, delOpts); delErr != nil {
		return fmt.Errorf("delete %s/%s: %w", gvr.Resource, opts.Name, delErr)
	}
	return nil
}

func (c *realClient) GetResource(ctx context.Context, opts GetResourceOptions) (string, error) {
	if opts.Name == "" {
		return "", errors.New("GetResource: name required")
	}
	gvr, namespaced, err := c.resolveKindToGVR(opts.Kind)
	if err != nil {
		return "", err
	}
	dyn, err := c.dynamicForCtx(ctx)
	if err != nil {
		return "", fmt.Errorf("get %s: impersonation client: %w", gvr.Resource, err)
	}
	var ri dynamic.ResourceInterface
	if namespaced {
		ri = dyn.Resource(gvr).Namespace(opts.Namespace)
	} else {
		ri = dyn.Resource(gvr)
	}
	obj, getErr := ri.Get(ctx, opts.Name, metav1.GetOptions{})
	if getErr != nil {
		return "", fmt.Errorf("get %s/%s: %w", gvr.Resource, opts.Name, getErr)
	}
	// unstructured.Unstructured 의 JSON shape 는 typed object 와 wire-compatible
	// (둘 다 K8s API 의 표준 직렬화 — metadata/spec/status 동일).
	bytes, jsonErr := json.Marshal(obj.Object)
	if jsonErr != nil {
		return "", fmt.Errorf("marshal resource: %w", jsonErr)
	}
	return string(bytes), nil
}

func (c *realClient) ListResources(ctx context.Context, opts ListResourcesOptions) (*ListResourcesResult, error) {
	gvr, namespaced, err := c.resolveKindToGVR(opts.Kind)
	if err != nil {
		return nil, err
	}
	listOpts := metav1.ListOptions{
		Limit:         opts.Limit,
		Continue:      opts.ContinueToken,
		LabelSelector: opts.LabelSelector,
	}
	dyn, err := c.dynamicForCtx(ctx)
	if err != nil {
		return nil, fmt.Errorf("list %s: impersonation client: %w", gvr.Resource, err)
	}
	var ri dynamic.ResourceInterface
	if namespaced {
		ri = dyn.Resource(gvr).Namespace(opts.Namespace)
	} else {
		ri = dyn.Resource(gvr)
	}
	list, listErr := ri.List(ctx, listOpts)
	if listErr != nil {
		return nil, fmt.Errorf("list %s: %w", gvr.Resource, listErr)
	}
	// UnstructuredList 는 MarshalJSON 이 list.Object 와 list.Items 를 병합해 proper K8s list
	// JSON (kind, apiVersion, metadata, items) 을 생성. list.Object 만 marshal 하면 items 누락.
	bytes, jsonErr := json.Marshal(list)
	if jsonErr != nil {
		return nil, fmt.Errorf("marshal list: %w", jsonErr)
	}
	return &ListResourcesResult{
		Items:         string(bytes),
		ContinueToken: list.GetContinue(),
		ReturnedCount: len(list.Items),
	}, nil
}

// ListByHelmRelease — helm release 가 만든 K8s 자원들을 한 번에 enumerate. backend 의
// HelmReleaseScanner 의 agent-side equivalent. label selector "app.kubernetes.io/instance=<release>"
// 로 11 종 자원 type 을 namespace 한정 list. PVC 의 boundPV 도 자동 포함.
//
// 각 type 별 list 는 idempotent — 부분 실패 시에도 모은 결과 반환 (debug log).
//
// 효율: backend 의 11 직렬 호출 (RTT * 11) 대비 in-cluster 호출 (latency ~ms each, 병렬 가능).
// 추후 errgroup 으로 병렬화 가능하나 11 개 정도면 직렬도 무난.
func (c *realClient) ListByHelmRelease(ctx context.Context, opts HelmReleaseResourcesOptions) ([]HelmReleaseResourceRef, error) {
	if opts.Release == "" {
		return nil, errors.New("ListByHelmRelease: release required")
	}
	const instanceLabel = "app.kubernetes.io/instance"
	selector := metav1.ListOptions{
		LabelSelector: instanceLabel + "=" + opts.Release,
	}

	out := make([]HelmReleaseResourceRef, 0, 32)
	ns := opts.Namespace

	// 헬퍼 — list call 의 결과를 out 에 추가. err 는 debug log 만 (best-effort).
	appendIfOK := func(kind, apiVersion string, items []metav1.ObjectMeta, listErr error, namespaced bool) {
		if listErr != nil {
			slog.Debug("ListByHelmRelease: list type failed",
				slog.String("kind", kind),
				slog.String("error", listErr.Error()))
			return
		}
		for _, m := range items {
			ref := HelmReleaseResourceRef{
				Kind: kind, APIVersion: apiVersion, Name: m.Name,
			}
			if namespaced {
				ref.Namespace = m.Namespace
			}
			out = append(out, ref)
		}
	}

	// apps/v1 — Deployment / StatefulSet / DaemonSet
	if dList, err := c.cs.AppsV1().Deployments(ns).List(ctx, selector); err != nil {
		appendIfOK("Deployment", "apps/v1", nil, err, true)
	} else {
		metas := make([]metav1.ObjectMeta, 0, len(dList.Items))
		for i := range dList.Items {
			metas = append(metas, dList.Items[i].ObjectMeta)
		}
		appendIfOK("Deployment", "apps/v1", metas, nil, true)
	}
	if sList, err := c.cs.AppsV1().StatefulSets(ns).List(ctx, selector); err != nil {
		appendIfOK("StatefulSet", "apps/v1", nil, err, true)
	} else {
		metas := make([]metav1.ObjectMeta, 0, len(sList.Items))
		for i := range sList.Items {
			metas = append(metas, sList.Items[i].ObjectMeta)
		}
		appendIfOK("StatefulSet", "apps/v1", metas, nil, true)
	}
	if dList, err := c.cs.AppsV1().DaemonSets(ns).List(ctx, selector); err != nil {
		appendIfOK("DaemonSet", "apps/v1", nil, err, true)
	} else {
		metas := make([]metav1.ObjectMeta, 0, len(dList.Items))
		for i := range dList.Items {
			metas = append(metas, dList.Items[i].ObjectMeta)
		}
		appendIfOK("DaemonSet", "apps/v1", metas, nil, true)
	}

	// v1 — Service / ConfigMap / Secret / ServiceAccount
	if list, err := c.cs.CoreV1().Services(ns).List(ctx, selector); err != nil {
		appendIfOK("Service", "v1", nil, err, true)
	} else {
		metas := make([]metav1.ObjectMeta, 0, len(list.Items))
		for i := range list.Items {
			metas = append(metas, list.Items[i].ObjectMeta)
		}
		appendIfOK("Service", "v1", metas, nil, true)
	}
	if list, err := c.cs.CoreV1().ConfigMaps(ns).List(ctx, selector); err != nil {
		appendIfOK("ConfigMap", "v1", nil, err, true)
	} else {
		metas := make([]metav1.ObjectMeta, 0, len(list.Items))
		for i := range list.Items {
			metas = append(metas, list.Items[i].ObjectMeta)
		}
		appendIfOK("ConfigMap", "v1", metas, nil, true)
	}
	if list, err := c.cs.CoreV1().Secrets(ns).List(ctx, selector); err != nil {
		appendIfOK("Secret", "v1", nil, err, true)
	} else {
		metas := make([]metav1.ObjectMeta, 0, len(list.Items))
		for i := range list.Items {
			metas = append(metas, list.Items[i].ObjectMeta)
		}
		appendIfOK("Secret", "v1", metas, nil, true)
	}
	if list, err := c.cs.CoreV1().ServiceAccounts(ns).List(ctx, selector); err != nil {
		appendIfOK("ServiceAccount", "v1", nil, err, true)
	} else {
		metas := make([]metav1.ObjectMeta, 0, len(list.Items))
		for i := range list.Items {
			metas = append(metas, list.Items[i].ObjectMeta)
		}
		appendIfOK("ServiceAccount", "v1", metas, nil, true)
	}

	// PVC + PV (bound). PVC 가 보유한 spec.volumeName 의 PV 도 자동 포함.
	if pvcList, err := c.cs.CoreV1().PersistentVolumeClaims(ns).List(ctx, selector); err != nil {
		appendIfOK("PersistentVolumeClaim", "v1", nil, err, true)
	} else {
		boundPVNames := make([]string, 0, len(pvcList.Items))
		metas := make([]metav1.ObjectMeta, 0, len(pvcList.Items))
		for i := range pvcList.Items {
			metas = append(metas, pvcList.Items[i].ObjectMeta)
			if vn := pvcList.Items[i].Spec.VolumeName; vn != "" {
				boundPVNames = append(boundPVNames, vn)
			}
		}
		appendIfOK("PersistentVolumeClaim", "v1", metas, nil, true)
		for _, pvName := range boundPVNames {
			if pv, err := c.cs.CoreV1().PersistentVolumes().Get(ctx, pvName, metav1.GetOptions{}); err == nil && pv != nil {
				out = append(out, HelmReleaseResourceRef{
					Kind: "PersistentVolume", APIVersion: "v1", Name: pv.Name,
				})
			}
		}
	}
	// label 직접 가진 PV (cluster-scoped). 위 boundPV 와 dedupe 안함 — list-overlap 은 흔치 않음.
	if list, err := c.cs.CoreV1().PersistentVolumes().List(ctx, selector); err == nil {
		for i := range list.Items {
			out = append(out, HelmReleaseResourceRef{
				Kind: "PersistentVolume", APIVersion: "v1", Name: list.Items[i].Name,
			})
		}
	}

	// rbac.authorization.k8s.io/v1 — Role / RoleBinding
	if list, err := c.cs.RbacV1().Roles(ns).List(ctx, selector); err != nil {
		appendIfOK("Role", "rbac.authorization.k8s.io/v1", nil, err, true)
	} else {
		metas := make([]metav1.ObjectMeta, 0, len(list.Items))
		for i := range list.Items {
			metas = append(metas, list.Items[i].ObjectMeta)
		}
		appendIfOK("Role", "rbac.authorization.k8s.io/v1", metas, nil, true)
	}
	if list, err := c.cs.RbacV1().RoleBindings(ns).List(ctx, selector); err != nil {
		appendIfOK("RoleBinding", "rbac.authorization.k8s.io/v1", nil, err, true)
	} else {
		metas := make([]metav1.ObjectMeta, 0, len(list.Items))
		for i := range list.Items {
			metas = append(metas, list.Items[i].ObjectMeta)
		}
		appendIfOK("RoleBinding", "rbac.authorization.k8s.io/v1", metas, nil, true)
	}

	// networking.k8s.io/v1 — Ingress
	if list, err := c.cs.NetworkingV1().Ingresses(ns).List(ctx, selector); err != nil {
		appendIfOK("Ingress", "networking.k8s.io/v1", nil, err, true)
	} else {
		metas := make([]metav1.ObjectMeta, 0, len(list.Items))
		for i := range list.Items {
			metas = append(metas, list.Items[i].ObjectMeta)
		}
		appendIfOK("Ingress", "networking.k8s.io/v1", metas, nil, true)
	}

	return out, nil
}

func (c *realClient) ApplyManifest(ctx context.Context, opts ApplyManifestOptions) (*ApplyManifestResult, error) {
	if strings.TrimSpace(opts.Manifest) == "" {
		return nil, errors.New("ApplyManifest: empty manifest")
	}
	if err := c.ensureDynamic(); err != nil {
		return nil, err
	}
	// impersonation 시 dynamic client 도 user 권한으로 patch (admission webhook 이
	// 평가하는 user identity 가 agent SA 아닌 실제 user 가 되도록).
	dyn, err := c.dynamicForCtx(ctx)
	if err != nil {
		return nil, fmt.Errorf("apply: impersonation client: %w", err)
	}
	fieldManager := opts.FieldManager
	if fieldManager == "" {
		fieldManager = "aipaas-agent"
	}
	applyOpts := metav1.PatchOptions{
		FieldManager: fieldManager,
	}
	if opts.Force {
		f := true
		applyOpts.Force = &f
	}
	if opts.DryRun {
		applyOpts.DryRun = []string{metav1.DryRunAll}
	}

	decoder := utilyaml.NewYAMLOrJSONDecoder(strings.NewReader(opts.Manifest), 4096)
	var applied []AppliedResource
	for {
		raw := map[string]interface{}{}
		if err := decoder.Decode(&raw); err != nil {
			if errors.Is(err, io.EOF) {
				break
			}
			return nil, fmt.Errorf("decode manifest: %w", err)
		}
		if len(raw) == 0 {
			continue     // empty doc (---).
		}
		u := &unstructured.Unstructured{Object: raw}
		gvk := u.GroupVersionKind()
		if gvk.Kind == "" {
			return nil, fmt.Errorf("manifest missing kind")
		}
		if u.GetName() == "" {
			return nil, fmt.Errorf("manifest missing metadata.name (kind=%s)", gvk.Kind)
		}

		mapping, mErr := c.mapper.RESTMapping(gvk.GroupKind(), gvk.Version)
		if mErr != nil {
			return nil, fmt.Errorf("REST mapping %s: %w", gvk, mErr)
		}

		// Namespace 결정.
		ns := u.GetNamespace()
		if ns == "" && mapping.Scope.Name() == meta.RESTScopeNameNamespace {
			ns = opts.DefaultNamespace
			u.SetNamespace(ns)
		}

		// Resource interface (namespaced / cluster-scoped).
		var resIface dynamic.ResourceInterface
		if mapping.Scope.Name() == meta.RESTScopeNameNamespace {
			resIface = dyn.Resource(mapping.Resource).Namespace(ns)
		} else {
			resIface = dyn.Resource(mapping.Resource)
		}

		// Server-side apply 는 PATCH ApplyPatchType + JSON body.
		body, jErr := json.Marshal(u.Object)
		if jErr != nil {
			return nil, fmt.Errorf("marshal %s: %w", gvk.Kind, jErr)
		}
		result, err := resIface.Patch(ctx, u.GetName(), types.ApplyPatchType, body, applyOpts)
		if err != nil {
			return nil, fmt.Errorf("apply %s/%s: %w", gvk.Kind, u.GetName(), err)
		}

		applied = append(applied, AppliedResource{
			APIVersion:      result.GetAPIVersion(),
			Kind:            result.GetKind(),
			Name:            result.GetName(),
			Namespace:       result.GetNamespace(),
			ResourceVersion: result.GetResourceVersion(),
			UID:             string(result.GetUID()),
		})
	}

	return &ApplyManifestResult{Applied: applied}, nil
}

// ExecInPod — kubectl exec 등가. SPDY-based remotecommand executor 사용.
//
// 구현 노트:
//   - REST request 는 corev1.PodExecOptions + SubResource("exec") 패턴 (client-go 표준).
//   - parameter codec 은 scheme.ParameterCodec — corev1 PodExecOptions 직렬화에 사용.
//   - StreamWithContext 가 ctx 종료 시 stream close, error 반환.
//   - Stderr 는 TTY=true 일 때 무시 (PTY 모드).
//   - 비정상 종료: exec.CodeExitError 로 wrap 되어 반환 — caller 가 unwrap 해 exit code 추출.
func (c *realClient) ExecInPod(ctx context.Context, opts PodExecOptions, streams ExecStreams) error {
	if opts.Pod == "" {
		return errors.New("ExecInPod: pod required")
	}
	if opts.Namespace == "" {
		return errors.New("ExecInPod: namespace required")
	}
	command := opts.Command
	if len(command) == 0 {
		command = []string{"/bin/sh"}
	}

	req := c.cs.CoreV1().RESTClient().Post().
		Resource("pods").
		Name(opts.Pod).
		Namespace(opts.Namespace).
		SubResource("exec")

	execOpts := &corev1.PodExecOptions{
		Container: opts.Container,
		Command:   command,
		Stdin:     opts.Stdin && streams.Stdin != nil,
		Stdout:    streams.Stdout != nil,
		Stderr:    streams.Stderr != nil && !opts.TTY,     // TTY=true 면 stderr 머지됨.
		TTY:       opts.TTY,
	}
	req.VersionedParams(execOpts, scheme.ParameterCodec)

	exec, err := remotecommand.NewSPDYExecutor(c.restConfig, "POST", req.URL())
	if err != nil {
		return fmt.Errorf("new SPDY executor: %w", err)
	}

	streamOpts := remotecommand.StreamOptions{
		Stdin:             streams.Stdin,
		Stdout:            streams.Stdout,
		Stderr:            streams.Stderr,
		Tty:               opts.TTY,
		TerminalSizeQueue: streams.ResizeQueue,
	}
	if opts.TTY {
		// PTY 모드: stderr 무시.
		streamOpts.Stderr = nil
	}

	if err := exec.StreamWithContext(ctx, streamOpts); err != nil {
		return fmt.Errorf("exec stream: %w", err)
	}
	return nil
}

// summarizePod — corev1.Pod → PodSummary. ContainerStatuses 의 ready count 집계.
func summarizePod(p *corev1.Pod) PodSummary {
	s := PodSummary{
		Name:      p.Name,
		Namespace: p.Namespace,
		Phase:     string(p.Status.Phase),
		NodeName:  p.Spec.NodeName,
		PodIP:     p.Status.PodIP,
	}
	if p.Status.StartTime != nil {
		s.StartTime = p.Status.StartTime.Time
	}
	s.ContainersTotal = len(p.Status.ContainerStatuses)
	for _, cs := range p.Status.ContainerStatuses {
		if cs.Ready {
			s.ContainersReady++
		}
	}
	return s
}

// inferDistribution — server version string 으로부터 distribution 추정. 정확도 best-effort.
func inferDistribution(gitVersion string) string {
	switch {
	case contains(gitVersion, "eks"):
		return "eks"
	case contains(gitVersion, "gke"):
		return "gke"
	case contains(gitVersion, "k3s"):
		return "k3s"
	case contains(gitVersion, "+aks"):
		return "aks"
	default:
		return "kubeadm"
	}
}

func contains(s, sub string) bool {
	for i := 0; i+len(sub) <= len(s); i++ {
		if s[i:i+len(sub)] == sub {
			return true
		}
	}
	return false
}
