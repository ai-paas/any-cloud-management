package providers

import (
	"fmt"
	"strings"
)

func NormalizeProvider(provider string) (string, error) {
	switch strings.ToLower(strings.TrimSpace(provider)) {
	case "", "aws":
		return "aws", nil
	case "gcp", "google", "googlecloud":
		return "gcp", nil
	case "azure", "msazure":
		return "azure", nil
	case "alibaba", "alicloud", "aliyun":
		return "alibaba", nil
	case "openstack", "open-stack":
		return "openstack", nil
	case "proxmox", "proxmoxve", "pve":
		return "proxmox", nil
	case "oci", "oracle", "oraclecloud", "oraclecloudinfrastructure":
		return "oci", nil
	case "digitalocean", "digital-ocean", "do":
		return "digitalocean", nil
	default:
		return "", fmt.Errorf("unsupported provider %q", provider)
	}
}
