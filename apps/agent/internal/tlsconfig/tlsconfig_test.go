package tlsconfig

import (
	"os"
	"path/filepath"
	"testing"
)

func TestDialOption_Disabled_ReturnsPlaintext(t *testing.T) {
	cfg := Config{Enabled: false}
	opt, err := cfg.DialOption("localhost:9090")
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if opt == nil {
		t.Fatal("DialOption is nil")
	}
}

func TestDialOption_EnabledWithSkipVerify(t *testing.T) {
	cfg := Config{Enabled: true, InsecureSkipVerify: true}
	opt, err := cfg.DialOption("backend.example.com:9090")
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if opt == nil {
		t.Fatal("DialOption is nil")
	}
}

func TestDialOption_EnabledWithInvalidPEM_Errors(t *testing.T) {
	cfg := Config{Enabled: true, CACertPEM: "not a real PEM"}
	_, err := cfg.DialOption("backend:9090")
	if err == nil {
		t.Fatal("expected error for invalid PEM, got nil")
	}
}

func TestDialOption_EnabledWithPath_Errors_WhenFileMissing(t *testing.T) {
	cfg := Config{Enabled: true, CACertPath: "/tmp/does-not-exist-anycloud-agent-test.crt"}
	_, err := cfg.DialOption("backend:9090")
	if err == nil {
		t.Fatal("expected error for missing CA file, got nil")
	}
}

// validPEM 은 X.509 self-signed test CA — pure go test 환경에서 cert pool 적재 가능한지 검증.
// 본 cert 는 expire 됐을 수도 있지만 cert pool 적재 자체는 expiry 무관 (검증은 verify 시점).
const validPEM = `-----BEGIN CERTIFICATE-----
MIIBhTCCASugAwIBAgIQIRi6zePL6mKjOipn+dNuaTAKBggqhkjOPQQDAjASMRAw
DgYDVQQKEwdBY21lIENvMB4XDTE3MTAyMDE5NDMwNloXDTE4MTAyMDE5NDMwNlow
EjEQMA4GA1UEChMHQWNtZSBDbzBZMBMGByqGSM49AgEGCCqGSM49AwEHA0IABD0d
7VNhbWvZLWPuj/RtHFjvtJBEwOkhbN/BnnE8rnZR8+sbwnc/KhCk3FhnpHZnQz7B
5aETbbIgmuvewdjvSBSjYzBhMA4GA1UdDwEB/wQEAwICpDATBgNVHSUEDDAKBggr
BgEFBQcDATAPBgNVHRMBAf8EBTADAQH/MCkGA1UdEQQiMCCCDmxvY2FsaG9zdDo1
NDUzgg4xMjcuMC4wLjE6NTQ1MzAKBggqhkjOPQQDAgNIADBFAiEA2zpJEPQyz6/l
Wf86aX6PepsntZv2GYlA5UpabfT2EZICICpJ5h/iI+i341gBmLiAFQOyTDT+/wQc
6MF9+Yw1Yy0t
-----END CERTIFICATE-----
`

func TestDialOption_EnabledWithValidPEM(t *testing.T) {
	cfg := Config{Enabled: true, CACertPEM: validPEM}
	opt, err := cfg.DialOption("backend:9090")
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if opt == nil {
		t.Fatal("DialOption is nil")
	}
}

func TestDialOption_EnabledWithCertFile(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "ca.crt")
	if err := os.WriteFile(path, []byte(validPEM), 0o600); err != nil {
		t.Fatalf("write tmp cert: %v", err)
	}
	cfg := Config{Enabled: true, CACertPath: path}
	if _, err := cfg.DialOption("backend:9090"); err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
}

func TestStripPort(t *testing.T) {
	cases := []struct {
		in, want string
	}{
		{"backend.example.com:9090", "backend.example.com"},
		{"192.168.1.10:9090", "192.168.1.10"},
		{"[::1]:9090", "::1"},
		{"[fe80::1]:443", "fe80::1"},
		{"localhost", "localhost"},     // 포트 없으면 그대로
	}
	for _, c := range cases {
		if got := stripPort(c.in); got != c.want {
			t.Errorf("stripPort(%q) = %q, want %q", c.in, got, c.want)
		}
	}
}

func TestFromEnv(t *testing.T) {
	t.Setenv("BACKEND_GRPC_TLS_ENABLED", "true")
	t.Setenv("BACKEND_CA_CERT_PATH", "/etc/agent/ca.crt")
	t.Setenv("BACKEND_TLS_SERVER_NAME", "backend.example.com")
	t.Setenv("BACKEND_TLS_INSECURE_SKIP_VERIFY", "false")
	cfg := FromEnv()
	if !cfg.Enabled {
		t.Error("Enabled should be true")
	}
	if cfg.CACertPath != "/etc/agent/ca.crt" {
		t.Errorf("CACertPath = %q", cfg.CACertPath)
	}
	if cfg.ServerName != "backend.example.com" {
		t.Errorf("ServerName = %q", cfg.ServerName)
	}
	if cfg.InsecureSkipVerify {
		t.Error("InsecureSkipVerify should be false")
	}
}
