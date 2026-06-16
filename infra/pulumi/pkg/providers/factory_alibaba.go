//go:build alibaba || all

package providers

import "anycloud/infra/pulumi/pkg/providers/alibaba"

func init() {
	Register("alibaba", func() ClusterProvisioner { return alibaba.New() })
}
