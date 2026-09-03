// Package tlsconfig — agent ↔ backend gRPC 의 TLS dial options 빌더.
//
// 모든 5 곳의 gRPC dial (bootstrap.Register / core.RunStream / rotation.Rotate /
// exec.PodExec / logstream.StreamPodLogs) 가 같은 credentials 를 써야 일관성 보장 —
// 본 패키지 단일 진입점.
//
// 환경 변수:
//   BACKEND_GRPC_TLS_ENABLED         "true" → TLS dial. default plaintext.
//   BACKEND_CA_CERT_PATH             CA cert PEM 파일 경로 (Secret/ConfigMap mount).
//   BACKEND_CA_CERT_PEM              inline PEM 내용 (chart 가 직접 주입할 때 — path 보다 우선).
//   BACKEND_TLS_SERVER_NAME          SNI override. 비우면 backend addr 의 host 부분 사용.
//   BACKEND_TLS_INSECURE_SKIP_VERIFY "true" 면 cert 검증 skip — dev/self-signed 만.
//
// 우선순위: PEM > path > system roots > skip-verify.
package tlsconfig

import (
	"crypto/tls"
	"crypto/x509"
	"errors"
	"fmt"
	"os"
	"strings"

	"google.golang.org/grpc"
	"google.golang.org/grpc/credentials"
	"google.golang.org/grpc/credentials/insecure"
)

// Config — 단일 dial 의 TLS 결정 인자. 일반적으로 main 에서 env 로 채워 모든 dial 에 전달.
//
// mTLS 폐기. server-side TLS 만 지원 (Rancher 와 동일).
type Config struct {
	Enabled            bool
	CACertPath         string
	CACertPEM          string // inline (e.g. helm values 로 직접 주입)
	ServerName         string // SNI override. 빈값이면 host:port 의 host 추출.
	InsecureSkipVerify bool   // dev only
}

// FromEnv — 환경변수에서 Config 빌드. 어디서든 호출 안전 (purely 읽기만).
func FromEnv() Config {
	return Config{
		Enabled:            strings.EqualFold(os.Getenv("BACKEND_GRPC_TLS_ENABLED"), "true"),
		CACertPath:         os.Getenv("BACKEND_CA_CERT_PATH"),
		CACertPEM:          os.Getenv("BACKEND_CA_CERT_PEM"),
		ServerName:         os.Getenv("BACKEND_TLS_SERVER_NAME"),
		InsecureSkipVerify: strings.EqualFold(os.Getenv("BACKEND_TLS_INSECURE_SKIP_VERIFY"), "true"),
	}
}

// DialOption — grpc.DialContext / grpc.NewClient 에 그대로 넘길 transport credentials.
// 모든 5 곳의 dial 이 본 함수를 호출.
//
// backendAddr 는 server name 자동 추출용 — "host:port" 의 host. SNI override 가 있으면 무시.
func (c Config) DialOption(backendAddr string) (grpc.DialOption, error) {
	if !c.Enabled {
		return grpc.WithTransportCredentials(insecure.NewCredentials()), nil
	}

	tlsCfg := &tls.Config{
		MinVersion: tls.VersionTLS12,
	}

	if c.InsecureSkipVerify {
		tlsCfg.InsecureSkipVerify = true     // nolint:gosec — dev only, opt-in via env
	} else {
		pool, err := buildCertPool(c)
		if err != nil {
			return nil, err
		}
		tlsCfg.RootCAs = pool
	}

	// SNI — explicit override 우선, 아니면 backend addr 의 host 부분.
	if c.ServerName != "" {
		tlsCfg.ServerName = c.ServerName
	} else if backendAddr != "" {
		tlsCfg.ServerName = stripPort(backendAddr)
	}

	return grpc.WithTransportCredentials(credentials.NewTLS(tlsCfg)), nil
}

// buildCertPool — CA cert 로딩. PEM 우선, 그 다음 path, 둘 다 없으면 system roots.
func buildCertPool(c Config) (*x509.CertPool, error) {
	if c.CACertPEM != "" {
		pool := x509.NewCertPool()
		if !pool.AppendCertsFromPEM([]byte(c.CACertPEM)) {
			return nil, errors.New("BACKEND_CA_CERT_PEM is not valid PEM")
		}
		return pool, nil
	}
	if c.CACertPath != "" {
		data, err := os.ReadFile(c.CACertPath)
		if err != nil {
			return nil, fmt.Errorf("read BACKEND_CA_CERT_PATH=%s: %w", c.CACertPath, err)
		}
		pool := x509.NewCertPool()
		if !pool.AppendCertsFromPEM(data) {
			return nil, fmt.Errorf("BACKEND_CA_CERT_PATH=%s is not valid PEM", c.CACertPath)
		}
		return pool, nil
	}
	// System roots (Let's Encrypt 등 publicly-trusted 인 경우).
	pool, err := x509.SystemCertPool()
	if err != nil {
		return nil, fmt.Errorf("load system cert pool: %w", err)
	}
	return pool, nil
}

// stripPort — "host:port" → "host". IPv6 [::1]:9090 도 안전.
func stripPort(hostPort string) string {
	// IPv6 with brackets.
	if strings.HasPrefix(hostPort, "[") {
		end := strings.Index(hostPort, "]")
		if end > 0 {
			return hostPort[1:end]
		}
	}
	// IPv4 / hostname.
	if idx := strings.LastIndex(hostPort, ":"); idx > 0 {
		return hostPort[:idx]
	}
	return hostPort
}
