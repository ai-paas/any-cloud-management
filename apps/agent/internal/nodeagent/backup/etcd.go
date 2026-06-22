// Package backup — backup-agent 의 etcd / PKI 백업 실행기.
//
// etcd.go — `etcdctl snapshot save <tmpfile>` 실행 후 file 을 server-streaming chunk 로 전송.
package backup

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
)

// EtcdDefaults — kubeadm 기본 cert 경로.
const (
	DefaultEtcdEndpoint = "https://127.0.0.1:2379"
	DefaultEtcdCa       = "/etc/kubernetes/pki/etcd/ca.crt"
	DefaultEtcdCert     = "/etc/kubernetes/pki/etcd/healthcheck-client.crt"
	DefaultEtcdKey      = "/etc/kubernetes/pki/etcd/healthcheck-client.key"
	DefaultChunkSize    = 1024 * 1024 // 1 MB
	MinChunkSize        = 16 * 1024   // 16 KB
	MaxChunkSize        = 4 * 1024 * 1024 // 4 MB (gRPC default max msg size)
)

// EtcdBackupOptions — caller 가 채워서 RunEtcdBackup 에 전달.
type EtcdBackupOptions struct {
	Endpoint       string
	CaCertPath     string
	ClientCertPath string
	ClientKeyPath  string
	ChunkSize      int
}

func (o *EtcdBackupOptions) applyDefaults() {
	if o.Endpoint == "" {
		o.Endpoint = DefaultEtcdEndpoint
	}
	if o.CaCertPath == "" {
		o.CaCertPath = DefaultEtcdCa
	}
	if o.ClientCertPath == "" {
		o.ClientCertPath = DefaultEtcdCert
	}
	if o.ClientKeyPath == "" {
		o.ClientKeyPath = DefaultEtcdKey
	}
	if o.ChunkSize < MinChunkSize {
		o.ChunkSize = DefaultChunkSize
	}
	if o.ChunkSize > MaxChunkSize {
		o.ChunkSize = MaxChunkSize
	}
}

// ChunkSink — 한 chunk 를 caller 에게 push. last chunk 는 metadata 포함.
// gRPC server-streaming 의 stream.Send 에 wrapping.
type ChunkSink func(seq int, payload []byte, isLast bool, totalSha256 string, totalSize int64, metadata string) error

// RunEtcdBackup — etcdctl snapshot save 실행 후 결과 file 을 ChunkSink 로 chunk 전송.
//
// 절차:
//   1. mktemp /tmp/etcd-backup-*.db
//   2. etcdctl snapshot save <tmpfile>  — environment ETCDCTL_API=3 강제
//   3. file 을 chunk 단위로 읽어 sink.Send
//   4. last chunk 에 SHA-256 + size + etcd_version metadata
//   5. tmpfile 삭제
//
// caller (gRPC handler) 가 stream 종료 처리 책임.
func RunEtcdBackup(ctx context.Context, opts EtcdBackupOptions, sink ChunkSink) error {
	opts.applyDefaults()

	// 1. tmp file
	tmpFile, err := os.CreateTemp("", "etcd-backup-*.db")
	if err != nil {
		return fmt.Errorf("create temp: %w", err)
	}
	tmpPath := tmpFile.Name()
	_ = tmpFile.Close()
	defer os.Remove(tmpPath)

	// 2. etcdctl snapshot save — V3 API 필수.
	cmd := exec.CommandContext(ctx, "etcdctl",
		"--endpoints", opts.Endpoint,
		"--cacert", opts.CaCertPath,
		"--cert", opts.ClientCertPath,
		"--key", opts.ClientKeyPath,
		"snapshot", "save", tmpPath)
	cmd.Env = append(os.Environ(), "ETCDCTL_API=3")
	out, err := cmd.CombinedOutput()
	if err != nil {
		return fmt.Errorf("etcdctl snapshot save: %w (output: %s)", err, truncateBytes(out, 512))
	}

	// 3. etcd 버전 (best-effort, metadata 채울 때 사용).
	etcdVersion := detectEtcdVersion(ctx, opts)

	// 4. file → chunk stream.
	f, err := os.Open(tmpPath)
	if err != nil {
		return fmt.Errorf("open snapshot: %w", err)
	}
	defer f.Close()

	stat, err := f.Stat()
	if err != nil {
		return fmt.Errorf("stat snapshot: %w", err)
	}
	totalSize := stat.Size()

	return streamFileChunks(f, opts.ChunkSize, totalSize, fmt.Sprintf("etcd_version=%s", etcdVersion), sink)
}

// streamFileChunks — 공통 헬퍼. file 을 chunk 별 sink 호출 + SHA 누적.
func streamFileChunks(r io.Reader, chunkSize int, totalSize int64, metadata string, sink ChunkSink) error {
	hash := sha256.New()
	buf := make([]byte, chunkSize)
	seq := 0

	// 한 번 미리 읽어 EOF 여부를 알아야 마지막 chunk 에 metadata 를 채울 수 있음.
	for {
		n, readErr := io.ReadFull(r, buf)
		if n > 0 {
			seq++
			hash.Write(buf[:n])
			isLast := readErr == io.EOF || readErr == io.ErrUnexpectedEOF
			var sha string
			var size int64
			var meta string
			if isLast {
				sha = hex.EncodeToString(hash.Sum(nil))
				size = totalSize
				meta = metadata
			}
			if err := sink(seq, buf[:n], isLast, sha, size, meta); err != nil {
				return fmt.Errorf("sink chunk %d: %w", seq, err)
			}
			if isLast {
				return nil
			}
		}
		if readErr != nil {
			if readErr == io.EOF {
				// payload 0 인 마지막 chunk — 빈 chunk 라도 last 표식만 전송.
				seq++
				sha := hex.EncodeToString(hash.Sum(nil))
				return sink(seq, nil, true, sha, totalSize, metadata)
			}
			return fmt.Errorf("read chunk: %w", readErr)
		}
	}
}

// detectEtcdVersion — etcdctl version 또는 endpoint 자체에서 추출. 실패 시 "unknown".
func detectEtcdVersion(ctx context.Context, opts EtcdBackupOptions) string {
	cmd := exec.CommandContext(ctx, "etcdctl",
		"--endpoints", opts.Endpoint,
		"--cacert", opts.CaCertPath,
		"--cert", opts.ClientCertPath,
		"--key", opts.ClientKeyPath,
		"endpoint", "status", "--write-out=simple")
	cmd.Env = append(os.Environ(), "ETCDCTL_API=3")
	out, err := cmd.Output()
	if err != nil {
		return "unknown"
	}
	// "127.0.0.1:2379, abc, 3.5.10, ..." — 3번째 token.
	parts := strings.Split(strings.TrimSpace(string(out)), ",")
	if len(parts) >= 3 {
		return strings.TrimSpace(parts[2])
	}
	return "unknown"
}

func truncateBytes(b []byte, max int) string {
	if len(b) <= max {
		return string(b)
	}
	return string(b[:max]) + "...(truncated)"
}

// EnsureControlPlane — 본 노드가 etcd 가 동작하는 control-plane 인지 best-effort 확인.
// /etc/kubernetes/manifests/etcd.yaml 존재 여부 + etcd cert 디렉토리 확인.
func EnsureControlPlane() error {
	if _, err := os.Stat("/etc/kubernetes/manifests/etcd.yaml"); err != nil {
		return fmt.Errorf("not a control-plane (no /etc/kubernetes/manifests/etcd.yaml): %w", err)
	}
	if _, err := os.Stat(filepath.Dir(DefaultEtcdCa)); err != nil {
		return fmt.Errorf("etcd PKI dir missing: %w", err)
	}
	return nil
}
