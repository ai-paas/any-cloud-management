// cluster-agent 의 backup orchestrator.
//
// node-agent 의 server-streaming BackupEtcd / BackupPki 를 호출하고 chunk 를 buffer 에 모은 뒤
// 단일 CommandResponse.binary_payload 로 backend 에 반환한다. PoC 한계: 100 MB.
//
// 실제 운영의 multi-GB etcd 는 별도 streaming proxy 필요. 그 시점에는 본 buffer 코드 제거
// 하고 reverse-tunnel 자체가 streaming 지원하도록 cluster-agent ↔ backend stream 재설계.
package controller

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"io"
	"time"

	agentv1 "anycloud/agent/internal/gen/agent/v1"
	nav1 "anycloud/agent/internal/gen/nodeagent/v1"
	corev1 "k8s.io/api/core/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"

	"google.golang.org/grpc"
	"google.golang.org/grpc/credentials/insecure"
	"google.golang.org/protobuf/types/known/structpb"
)

const (
	// 100 MB PoC 한계 — 작은~중간 cluster (etcd ~50-80MB) cover. 운영의 multi-GB 는 별도 streaming proxy.
	backupMaxBufferBytes = 100 * 1024 * 1024
	backupStreamTimeout  = 10 * time.Minute
	backupNodeAgentPort  = 9090
	// node-agent DaemonSet 위치 — aipaas-system namespace, app=backup-agent label.
	nodeAgentNamespace = "aipaas-system"
	nodeAgentLabel     = "app=backup-agent"
)

// isMaster — control-plane node 검사. K8s 1.20+ 의 표준 label + 구 호환용 master label 둘 다.
func isMaster(n corev1.Node) bool {
	if _, ok := n.Labels["node-role.kubernetes.io/control-plane"]; ok {
		return true
	}
	if _, ok := n.Labels["node-role.kubernetes.io/master"]; ok {
		return true
	}
	return false
}

// backupEtcd — BACKUP_ETCD 핸들러. control-plane 노드 1개 선택 → node-agent streaming → buffer 반환.
func (d *Dispatcher) backupEtcd(ctx context.Context, cmd *agentv1.CommandRequest) *agentv1.CommandResponse {
	if d.kube == nil {
		return errorResponse(agentv1.Status_AGENT_UNAVAILABLE, "K8S_CLIENT_NIL", "K8s client not initialized")
	}

	hostIP, nodeName, err := d.pickControlPlaneNodeAgent(ctx)
	if err != nil {
		return errorResponse(agentv1.Status_FAILED, err.Error(), err.Error())
	}

	req := &nav1.BackupEtcdRequest{
		Endpoint:       getStringParam(cmd, "endpoint"),
		CaCertPath:     getStringParam(cmd, "ca_cert_path"),
		ClientCertPath: getStringParam(cmd, "client_cert_path"),
		ClientKeyPath:  getStringParam(cmd, "client_key_path"),
		ChunkSize:      int32(parseIntOr(getStringParam(cmd, "chunk_size"), 0)),
	}

	bufCtx, cancel := context.WithTimeout(ctx, backupStreamTimeout)
	defer cancel()

	conn, err := grpc.NewClient(fmt.Sprintf("%s:%d", hostIP, backupNodeAgentPort),
		grpc.WithTransportCredentials(insecure.NewCredentials()))
	if err != nil {
		return errorResponse(agentv1.Status_FAILED, "NODE_AGENT_DIAL_FAILED", err.Error())
	}
	defer conn.Close()
	client := nav1.NewNodeAgentClient(conn)

	stream, err := client.BackupEtcd(bufCtx, req)
	if err != nil {
		return errorResponse(agentv1.Status_FAILED, "NODE_AGENT_RPC_FAILED", err.Error())
	}

	payload, lastChunk, err := bufferChunks(stream)
	if err != nil {
		return errorResponse(agentv1.Status_FAILED, "BACKUP_FAILED", err.Error())
	}

	return okBackupResponse(payload, lastChunk, nodeName)
}

// backupPki — BACKUP_PKI 핸들러. 동일 패턴, control-plane 검사는 PKI 의미상 노드 선택만 한다.
func (d *Dispatcher) backupPki(ctx context.Context, cmd *agentv1.CommandRequest) *agentv1.CommandResponse {
	if d.kube == nil {
		return errorResponse(agentv1.Status_AGENT_UNAVAILABLE, "K8S_CLIENT_NIL", "K8s client not initialized")
	}

	hostIP, nodeName, err := d.pickControlPlaneNodeAgent(ctx)
	if err != nil {
		return errorResponse(agentv1.Status_FAILED, err.Error(), err.Error())
	}

	req := &nav1.BackupPkiRequest{
		ChunkSize: int32(parseIntOr(getStringParam(cmd, "chunk_size"), 0)),
	}
	if raw := getStringParam(cmd, "include_paths"); raw != "" {
		// JSON array 문자열 — Java 측 caller 가 명시.
		var paths []string
		if err := jsonUnmarshalSlice(raw, &paths); err == nil {
			req.IncludePaths = paths
		}
	}

	bufCtx, cancel := context.WithTimeout(ctx, backupStreamTimeout)
	defer cancel()

	conn, err := grpc.NewClient(fmt.Sprintf("%s:%d", hostIP, backupNodeAgentPort),
		grpc.WithTransportCredentials(insecure.NewCredentials()))
	if err != nil {
		return errorResponse(agentv1.Status_FAILED, "NODE_AGENT_DIAL_FAILED", err.Error())
	}
	defer conn.Close()
	client := nav1.NewNodeAgentClient(conn)

	stream, err := client.BackupPki(bufCtx, req)
	if err != nil {
		return errorResponse(agentv1.Status_FAILED, "NODE_AGENT_RPC_FAILED", err.Error())
	}

	payload, lastChunk, err := bufferChunks(stream)
	if err != nil {
		return errorResponse(agentv1.Status_FAILED, "BACKUP_FAILED", err.Error())
	}

	return okBackupResponse(payload, lastChunk, nodeName)
}

// chunkStream — BackupEtcd / BackupPki 양쪽 호환되는 stream interface.
type chunkStream interface {
	Recv() (*nav1.BackupChunk, error)
}

// bufferChunks — server-streaming chunk 들을 한 buffer 에 누적. SHA-256 자체 검증. 100MB 초과 시 abort.
func bufferChunks(stream chunkStream) ([]byte, *nav1.BackupChunk, error) {
	buf := &bytes.Buffer{}
	hash := sha256.New()
	var last *nav1.BackupChunk
	for {
		c, err := stream.Recv()
		if err == io.EOF {
			break
		}
		if err != nil {
			return nil, nil, fmt.Errorf("recv chunk: %w", err)
		}
		if len(c.Payload) > 0 {
			if buf.Len()+len(c.Payload) > backupMaxBufferBytes {
				return nil, nil, fmt.Errorf("backup exceeds %d byte limit — use streaming proxy", backupMaxBufferBytes)
			}
			buf.Write(c.Payload)
			hash.Write(c.Payload)
		}
		if c.IsLast {
			last = c
		}
	}
	if last == nil {
		return nil, nil, fmt.Errorf("stream ended without is_last chunk")
	}
	computed := hex.EncodeToString(hash.Sum(nil))
	if last.TotalSha256 != "" && last.TotalSha256 != computed {
		return nil, nil, fmt.Errorf("SHA-256 mismatch: computed=%s declared=%s", computed, last.TotalSha256)
	}
	return buf.Bytes(), last, nil
}

// okBackupResponse — binary_payload 로 backup bytes 반환. result.fields 에 metadata.
func okBackupResponse(payload []byte, last *nav1.BackupChunk, nodeName string) *agentv1.CommandResponse {
	meta := map[string]interface{}{
		"size_bytes": float64(last.TotalSize),
		"sha256":     last.TotalSha256,
		"metadata":   last.Metadata,
		"node_name":  nodeName,
	}
	st, _ := structpb.NewStruct(meta)
	return &agentv1.CommandResponse{
		Status:        agentv1.Status_OK,
		Result:        st,
		BinaryPayload: payload,
	}
}

// pickControlPlaneNodeAgent — control-plane 노드 중 backup-agent 가 살아있는 첫 노드 선택.
// 반환: (hostIP, nodeName, error_code_or_nil).
func (d *Dispatcher) pickControlPlaneNodeAgent(ctx context.Context) (string, string, error) {
	cs := d.kube.Clientset()

	nodes, err := cs.CoreV1().Nodes().List(ctx, metav1.ListOptions{})
	if err != nil {
		return "", "", fmt.Errorf("NODE_LIST_FAILED")
	}
	var masters []corev1.Node
	for _, n := range nodes.Items {
		if isMaster(n) {
			masters = append(masters, n)
		}
	}
	if len(masters) == 0 {
		return "", "", fmt.Errorf("NO_CONTROL_PLANE")
	}

	pods, err := cs.CoreV1().Pods(nodeAgentNamespace).List(ctx, metav1.ListOptions{
		LabelSelector: nodeAgentLabel,
	})
	if err != nil {
		return "", "", fmt.Errorf("NODE_AGENT_DISCOVERY_FAILED")
	}
	hostByNode := make(map[string]string)
	for _, p := range pods.Items {
		if p.Spec.NodeName != "" && p.Status.HostIP != "" {
			hostByNode[p.Spec.NodeName] = p.Status.HostIP
		}
	}

	for _, m := range masters {
		if ip, ok := hostByNode[m.Name]; ok {
			return ip, m.Name, nil
		}
	}
	return "", "", fmt.Errorf("NO_NODE_AGENT")
}

// jsonUnmarshalSlice — encoding/json 직접 사용 회피 helper (테스트성, 일관성).
func jsonUnmarshalSlice(raw string, out *[]string) error {
	return jsonDecodeStrSlice([]byte(raw), out)
}

func parseIntOr(s string, fallback int) int {
	if s == "" {
		return fallback
	}
	var n int
	if _, err := fmt.Sscanf(s, "%d", &n); err != nil {
		return fallback
	}
	return n
}
