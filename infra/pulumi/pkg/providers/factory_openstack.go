//go:build openstack || all

package providers

import "anycloud/infra/pulumi/pkg/providers/openstack"

func init() {
	Register("openstack", func() ClusterProvisioner { return openstack.New() })
}
