//go:build aws || all

package providers

import "anycloud/infra/pulumi/pkg/providers/aws"

func init() {
	Register("aws", func() ClusterProvisioner { return aws.New() })
}
