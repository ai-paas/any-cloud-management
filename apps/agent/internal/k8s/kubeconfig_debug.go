// TokenRequest 발급 + kubeconfig 보조용 ServerCAData/APIServerURL.
// CreateNodeDebugPod (kubectl debug node 등가).
// ListPodsRaw (annotation/label 기반 sweeper 가 활용).
package k8s

import (
	"context"
	"errors"
	"fmt"
	"os"
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
	Namespace          string
	ServiceAccount     string
	ExpirationSeconds  int64    // 60 미만이면 60 으로 clamp. 0 이면 default 3600.
}

type TokenRequestResult struct {
	Token              string
	ExpirationTimestamp time.Time     // server 가 부여한 실제 만료 시각.
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
// Node debug pod
// ============================================================================

type NodeDebugPodOptions struct {
	NodeName   string
	Namespace  string     // default "kube-system"
	Image      string     // default "registry.k8s.io/e2e-test-images/agnhost:2.40"
	PodName    string     // default "aipaas-node-debug-<ts>"
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
		image = "registry.k8s.io/e2e-test-images/agnhost:2.40"
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
	hostPid := true
	hostNet := true
	hostIpc := true

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
			NodeName:      opts.NodeName,
			HostPID:       hostPid,
			HostNetwork:   hostNet,
			HostIPC:       hostIpc,
			RestartPolicy: corev1.RestartPolicyNever,
			// 모든 노드 (master/taint 포함) 에 스케줄.
			Tolerations: []corev1.Toleration{{Operator: corev1.TolerationOpExists}},
			Containers: []corev1.Container{{
				Name:    "debug",
				Image:   image,
				Stdin:   true,
				TTY:     true,
				Command: []string{"nsenter"},
				Args:    []string{"-t", "1", "-m", "-u", "-i", "-n", "-p", "--", "bash"},
				SecurityContext: &corev1.SecurityContext{
					Privileged:               &priv,
					AllowPrivilegeEscalation: &t,
				},
			}},
		},
	}

	created, err := c.cs.CoreV1().Pods(ns).Create(ctx, pod, metav1.CreateOptions{})
	if err != nil {
		return NodeDebugPodResult{}, fmt.Errorf("create debug pod: %w", err)
	}
	return NodeDebugPodResult{
		Namespace: created.Namespace,
		PodName:   created.Name,
		ExpiresAt: time.Now().Add(time.Duration(ttl) * time.Second),
	}, nil
}
