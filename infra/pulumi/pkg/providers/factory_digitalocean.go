//go:build digitalocean || all

package providers

import "anycloud/infra/pulumi/pkg/providers/digitalocean"

func init() {
	Register("digitalocean", func() ClusterProvisioner { return digitalocean.New() })
}
