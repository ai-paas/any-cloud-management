//go:build gcp || all

package providers

import "anycloud/infra/pulumi/pkg/providers/gcp"

func init() {
	Register("gcp", func() ClusterProvisioner { return gcp.New() })
}
