//go:build proxmox || all

package providers

import "anycloud/infra/pulumi/pkg/providers/proxmox"

func init() {
	Register("proxmox", func() ClusterProvisioner { return proxmox.New() })
}
