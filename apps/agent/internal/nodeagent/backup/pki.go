// Package backup — pki.go: /etc/kubernetes/pki 디렉토리 tar+gzip → server-streaming.
//
// 보안 주의: PKI 는 cluster CA 비밀키 포함. 본 chunk 는 plaintext 로 stream — caller (cluster-agent
// → backend) 가 storage 에 올리기 전 호스트의 KEK 로 반드시 encrypt.
package backup

import (
	"archive/tar"
	"compress/gzip"
	"context"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strings"
)

const (
	DefaultPkiRoot = "/etc/kubernetes/pki"
)

// PkiBackupOptions — caller 가 채워서 RunPkiBackup 에 전달.
type PkiBackupOptions struct {
	// /etc/kubernetes/pki 기준 relative path. 비어 있으면 전체 tar.
	// 예: ["ca.crt", "ca.key", "sa.key", "sa.pub", "etcd"]
	IncludePaths []string
	// chunk size in bytes. 0 또는 too-small 이면 DefaultChunkSize (1MB).
	ChunkSize int
}

func (o *PkiBackupOptions) applyDefaults() {
	if o.ChunkSize < MinChunkSize {
		o.ChunkSize = DefaultChunkSize
	}
	if o.ChunkSize > MaxChunkSize {
		o.ChunkSize = MaxChunkSize
	}
}

// RunPkiBackup — /etc/kubernetes/pki (또는 IncludePaths 부분) 을 tar.gz 으로 묶어 ChunkSink 로 stream.
//
// 절차:
//   1. mktemp /tmp/pki-backup-*.tar.gz
//   2. include_paths 기준 (또는 전체) 탐색 → tar+gzip
//   3. file 을 chunk 별 sink.Send
//   4. last chunk 에 SHA-256 + size + file_count metadata
func RunPkiBackup(ctx context.Context, opts PkiBackupOptions, sink ChunkSink) error {
	opts.applyDefaults()

	if _, err := os.Stat(DefaultPkiRoot); err != nil {
		return fmt.Errorf("PKI root %s missing: %w", DefaultPkiRoot, err)
	}

	tmpFile, err := os.CreateTemp("", "pki-backup-*.tar.gz")
	if err != nil {
		return fmt.Errorf("create temp: %w", err)
	}
	tmpPath := tmpFile.Name()
	defer os.Remove(tmpPath)

	// tar.gz 작성.
	gw := gzip.NewWriter(tmpFile)
	tw := tar.NewWriter(gw)

	fileCount, uncompressed, err := tarPki(ctx, tw, opts.IncludePaths)
	if err != nil {
		_ = tw.Close()
		_ = gw.Close()
		_ = tmpFile.Close()
		return fmt.Errorf("tar pki: %w", err)
	}
	if err := tw.Close(); err != nil {
		_ = gw.Close()
		_ = tmpFile.Close()
		return fmt.Errorf("tar close: %w", err)
	}
	if err := gw.Close(); err != nil {
		_ = tmpFile.Close()
		return fmt.Errorf("gzip close: %w", err)
	}
	if err := tmpFile.Close(); err != nil {
		return fmt.Errorf("temp close: %w", err)
	}

	// chunk streaming.
	f, err := os.Open(tmpPath)
	if err != nil {
		return fmt.Errorf("open tar.gz: %w", err)
	}
	defer f.Close()

	stat, err := f.Stat()
	if err != nil {
		return fmt.Errorf("stat tar.gz: %w", err)
	}
	totalSize := stat.Size()
	metadata := fmt.Sprintf("file_count=%d,uncompressed_size=%d", fileCount, uncompressed)

	return streamFileChunks(f, opts.ChunkSize, totalSize, metadata, sink)
}

// tarPki — DefaultPkiRoot 또는 include_paths 의 각 항목을 tar 에 기록. 반환: (file 개수, 총 uncompressed size).
func tarPki(ctx context.Context, tw *tar.Writer, includePaths []string) (int, int64, error) {
	roots := []string{DefaultPkiRoot}
	if len(includePaths) > 0 {
		roots = make([]string, 0, len(includePaths))
		for _, p := range includePaths {
			if p == "" || strings.Contains(p, "..") {
				continue // 경로 traversal 차단.
			}
			roots = append(roots, filepath.Join(DefaultPkiRoot, p))
		}
	}

	var fileCount int
	var totalSize int64

	for _, root := range roots {
		err := filepath.Walk(root, func(path string, info os.FileInfo, err error) error {
			if ctx.Err() != nil {
				return ctx.Err()
			}
			if err != nil {
				return err
			}
			// tar archive name 은 /etc/kubernetes/pki 기준 relative — restore 시 호환.
			rel, relErr := filepath.Rel("/etc/kubernetes/pki", path)
			if relErr != nil {
				rel = path
			}
			rel = filepath.Join("pki", rel)

			hdr, hdrErr := tar.FileInfoHeader(info, "")
			if hdrErr != nil {
				return hdrErr
			}
			hdr.Name = rel

			if err := tw.WriteHeader(hdr); err != nil {
				return err
			}
			if !info.Mode().IsRegular() {
				return nil
			}
			f, openErr := os.Open(path)
			if openErr != nil {
				return openErr
			}
			written, copyErr := io.Copy(tw, f)
			_ = f.Close()
			if copyErr != nil {
				return copyErr
			}
			fileCount++
			totalSize += written
			return nil
		})
		if err != nil {
			return fileCount, totalSize, fmt.Errorf("walk %s: %w", root, err)
		}
	}
	return fileCount, totalSize, nil
}
