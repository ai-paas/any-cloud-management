package main

import (
	"anycloud/infra/pulumi/pkg/config"
	"anycloud/infra/pulumi/pkg/providers"
	"github.com/pulumi/pulumi/sdk/v3/go/pulumi"
)

func main() {
	pulumi.Run(func(ctx *pulumi.Context) error {
		spec := config.Load(ctx)

		provisioner, err := providers.New(spec.Provider)
		if err != nil {
			return err
		}

		outputs, err := provisioner.Provision(ctx, spec)
		if err != nil {
			return err
		}

		for key, value := range outputs {
			ctx.Export(key, value)
		}

		ctx.Export("summary", pulumi.Sprintf(
			"provider=%s cluster=%s masters=%d workers=%d kubernetes=%s",
			spec.Provider,
			spec.Name,
			spec.MasterCount,
			spec.WorkerCount,
			spec.KubernetesVersion,
		))

		return nil
	})
}
