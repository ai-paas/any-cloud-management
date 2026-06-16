//go:build azure || all

package providers

import "anycloud/infra/pulumi/pkg/providers/azure"

func init() {
	Register("azure", func() ClusterProvisioner { return azure.New() })
}
