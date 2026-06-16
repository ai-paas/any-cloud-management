package providers

import (
	"anycloud/infra/pulumi/pkg/model"
	"github.com/pulumi/pulumi/sdk/v3/go/pulumi"
)

type ClusterProvisioner interface {
	Provision(ctx *pulumi.Context, spec *model.ClusterSpec) (pulumi.Map, error)
}
