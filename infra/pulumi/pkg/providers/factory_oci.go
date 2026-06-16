//go:build oci || all

package providers

import "anycloud/infra/pulumi/pkg/providers/oci"

func init() {
	Register("oci", func() ClusterProvisioner { return oci.New() })
}
