// TokenRequest 발급 + kubeconfig 보조용 ServerCAData/APIServerURL.
// CreateNodeDebugPod (kubectl debug node 등가).
// ListPodsRaw (annotation/label 기반 sweeper 가 활용).
package k8s

import (
	"context"
	"errors"
	"fmt"
	"os"
	"strconv"
	"time"

	authnv1 "k8s.io/api/authentication/v1"
	corev1 "k8s.io/api/core/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
)

// ============================================================================
// Annotation 기반 sweeper 가 사용하는 label-filtered list.
// ============================================================================

func (c *realClient) ListPodsRaw(ctx context.Context, namespace, labelSelector string) ([]corev1.Pod, error) {
	pods, err := c.cs.CoreV1().Pods(namespace).List(ctx, metav1.ListOptions{
		LabelSelector: labelSelector,
		Limit:         500,
	})
	if err != nil {
		return nil, fmt.Errorf("list pods (ns=%s, selector=%s): %w", namespace, labelSelector, err)
	}
	return pods.Items, nil
}

// ============================================================================
// Token / kubeconfig
// ============================================================================

type TokenRequestOptions struct {
	Namespace         string
	ServiceAccount    string
	ExpirationSeconds int64 // 60 미만이면 60 으로 clamp. 0 이면 default 3600.
}

type TokenRequestResult struct {
	Token               string
	ExpirationTimestamp time.Time // server 가 부여한 실제 만료 시각.
}

func (c *realClient) IssueServiceAccountToken(ctx context.Context, opts TokenRequestOptions) (TokenRequestResult, error) {
	if opts.Namespace == "" || opts.ServiceAccount == "" {
		return TokenRequestResult{}, errors.New("namespace + service_account required")
	}
	expSec := opts.ExpirationSeconds
	if expSec <= 0 {
		expSec = 3600
	} else if expSec < 60 {
		expSec = 60
	}
	tr := &authnv1.TokenRequest{
		Spec: authnv1.TokenRequestSpec{
			ExpirationSeconds: &expSec,
		},
	}
	out, err := c.cs.CoreV1().ServiceAccounts(opts.Namespace).CreateToken(ctx, opts.ServiceAccount, tr, metav1.CreateOptions{})
	if err != nil {
		return TokenRequestResult{}, fmt.Errorf("token request: %w", err)
	}
	return TokenRequestResult{
		Token:               out.Status.Token,
		ExpirationTimestamp: out.Status.ExpirationTimestamp.Time,
	}, nil
}

// ServerCAData — in-cluster 인 경우 service account ca.crt. KUBECONFIG 인 경우 restConfig.TLSClientConfig.CAData
// 또는 CAFile 을 읽음. 둘 다 없으면 빈 byte (kubeconfig 발급 시 insecure-skip-tls-verify 가 필요 — 보안상 권장 X).
func (c *realClient) ServerCAData() ([]byte, error) {
	if c.restConfig == nil {
		return nil, errors.New("rest config not initialized")
	}
	if len(c.restConfig.TLSClientConfig.CAData) > 0 {
		return c.restConfig.TLSClientConfig.CAData, nil
	}
	if c.restConfig.TLSClientConfig.CAFile != "" {
		return os.ReadFile(c.restConfig.TLSClientConfig.CAFile)
	}
	// In-cluster 의 표준 위치 fallback.
	if data, err := os.ReadFile("/var/run/secrets/kubernetes.io/serviceaccount/ca.crt"); err == nil {
		return data, nil
	}
	return nil, errors.New("no CA data available")
}

func (c *realClient) APIServerURL() string {
	if c.restConfig == nil {
		return ""
	}
	return c.restConfig.Host
}

// ============================================================================
// debugContainerName — 웹이 exec 대상으로 지정하는 이름과 같아야 한다.
const debugContainerName = "debug"

// debugPodReadyTimeout — 호출자가 데드라인을 주지 않았을 때의 상한.
const debugPodReadyTimeout = 3 * time.Minute

// Node debug pod
// ============================================================================

// kubectl 과 k9s 가 들어 있는 이미지. 노드에 이 도구들이 깔려 있으리라 기대할 수 없다.
const defaultToolsImage = "ghcr.io/ai-paas/ops-shell:latest"

type NodeDebugPodOptions struct {
	NodeName  string
	Namespace string // default "kube-system"
	Image     string // default "registry.k8s.io/e2e-test-images/agnhost:2.40"
	PodName   string // default "aipaas-node-debug-<ts>"
	// ToolsShell — 호스트가 아니라 클러스터를 보는 셸. nsenter 없이 이미지를 그대로 실행하므로
	// kubectl, k9s 가 이미지에서 온다. 노드에 그 도구들이 깔려 있으리라 기대할 수 없다.
	ToolsShell bool
	// ToolsShell 일 때 붙일 SA. 없으면 kubectl 이 모든 호출에서 forbidden 을 받는다.
	ServiceAccount string
	// 생성된 debug pod 의 활성 기간 (cleanup 은 호출자/운영자 책임 — TTL 만 정보 제공).
	TTLSeconds int64
}

type NodeDebugPodResult struct {
	Namespace string
	PodName   string
	ExpiresAt time.Time
}

// CreateNodeDebugPod — kubectl debug node/<name> 등가. host PID/Network/IPC namespace + privileged.
// nsenter 1 -t 1 -m -u -i -n -p -- bash 를 entrypoint 로 사용 (host root shell).
func (c *realClient) CreateNodeDebugPod(ctx context.Context, opts NodeDebugPodOptions) (NodeDebugPodResult, error) {
	if opts.NodeName == "" {
		return NodeDebugPodResult{}, errors.New("node_name required")
	}
	ns := opts.Namespace
	if ns == "" {
		ns = "kube-system"
	}
	image := opts.Image
	if image == "" {
		if opts.ToolsShell {
			image = defaultToolsImage
		} else {
			image = "registry.k8s.io/e2e-test-images/agnhost:2.40"
		}
	}
	name := opts.PodName
	if name == "" {
		name = fmt.Sprintf("aipaas-node-debug-%d", time.Now().UnixNano()/int64(time.Second))
	}
	ttl := opts.TTLSeconds
	if ttl <= 0 {
		ttl = 1800
	}

	t := true
	priv := true
	host := !opts.ToolsShell

	container := corev1.Container{
		Name:  debugContainerName,
		Image: image,
		Stdin: true,
		TTY:   true,
	}
	if opts.ToolsShell {
		// 셸을 붙일 때까지 살아 있기만 하면 된다. exec 가 들어와 bash 를 연다.
		container.Command = []string{"sleep"}
		container.Args = []string{strconv.FormatInt(ttl, 10)}
	} else {
		container.Command = []string{"nsenter"}
		container.Args = []string{"-t", "1", "-m", "-u", "-i", "-n", "-p", "--", "bash"}
		container.SecurityContext = &corev1.SecurityContext{
			Privileged:               &priv,
			AllowPrivilegeEscalation: &t,
		}
	}

	pod := &corev1.Pod{
		ObjectMeta: metav1.ObjectMeta{
			Name:      name,
			Namespace: ns,
			Labels: map[string]string{
				"app.kubernetes.io/managed-by": "aipaas-cluster-agent",
				"aipaas/debug-node":            opts.NodeName,
			},
			Annotations: map[string]string{
				"aipaas.io/expires-at": time.Now().Add(time.Duration(ttl) * time.Second).Format(time.RFC3339),
			},
		},
		Spec: corev1.PodSpec{
			NodeName:           opts.NodeName,
			ServiceAccountName: opts.ServiceAccount,
			HostPID:            host,
			HostNetwork:        host,
			HostIPC:            host,
			RestartPolicy:      corev1.RestartPolicyNever,
			// 모든 노드 (master/taint 포함) 에 스케줄.
			Tolerations: []corev1.Toleration{{Operator: corev1.TolerationOpExists}},
			Containers:  []corev1.Container{container},
		},
	}

	created, err := c.cs.CoreV1().Pods(ns).Create(ctx, pod, metav1.CreateOptions{})
	if err != nil {
		return NodeDebugPodResult{}, fmt.Errorf("create debug pod: %w", err)
	}
	if err := c.waitDebugContainerRunning(ctx, created.Namespace, created.Name); err != nil {
		return NodeDebugPodResult{}, err
	}
	return NodeDebugPodResult{
		Namespace: created.Namespace,
		PodName:   created.Name,
		ExpiresAt: time.Now().Add(time.Duration(ttl) * time.Second),
	}, nil
}

// waitDebugContainerRunning — 컨테이너가 뜰 때까지 기다린다.
//
// 이름만 돌려주면 호출자가 곧바로 exec 을 걸고 "container not found (debug)" 로 끝난다. 노드에
// 이미지가 없으면 pull 에 1분 넘게 걸려 거의 매번 걸린다. 기다리는 상한은 ctx 가 정한다.
func (c *realClient) waitDebugContainerRunning(ctx context.Context, ns, name string) error {
	// 호출자가 데드라인을 주지 않아도 영원히 붙잡지 않는다. 이미지 pull 이 이보다 오래 걸리면
	// 기다려서 될 일이 아니다.
	if _, ok := ctx.Deadline(); !ok {
		var cancel context.CancelFunc
		ctx, cancel = context.WithTimeout(ctx, debugPodReadyTimeout)
		defer cancel()
	}
	ticker := time.NewTicker(time.Second)
	defer ticker.Stop()
	var lastReason string
	for {
		pod, err := c.cs.CoreV1().Pods(ns).Get(ctx, name, metav1.GetOptions{})
		if err == nil {
			for _, cs := range pod.Status.ContainerStatuses {
				if cs.Name != debugContainerName {
					continue
				}
				if cs.State.Running != nil {
					return nil
				}
				// ImagePullBackOff 처럼 기다려도 낫지 않는 상태는 그대로 알린다.
				if w := cs.State.Waiting; w != nil {
					lastReason = w.Reason
					if w.Reason == "ImagePullBackOff" || w.Reason == "ErrImagePull" ||
						w.Reason == "CreateContainerConfigError" {
						return fmt.Errorf("debug container %s: %s: %s", w.Reason, name, w.Message)
					}
				}
				if cs.State.Terminated != nil {
					return fmt.Errorf("debug container exited before use: %s", cs.State.Terminated.Reason)
				}
			}
			if pod.Status.Phase == corev1.PodFailed {
				return fmt.Errorf("debug pod failed: %s", pod.Status.Reason)
			}
		}
		select {
		case <-ctx.Done():
			if lastReason == "" {
				lastReason = "Pending"
			}
			return fmt.Errorf("debug container not ready in time (%s): %s", lastReason, name)
		case <-ticker.C:
		}
	}
}
