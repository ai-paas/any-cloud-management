package config

import (
	"anycloud/infra/pulumi/pkg/model"
	"github.com/pulumi/pulumi/sdk/v3/go/pulumi"
	pulumiconfig "github.com/pulumi/pulumi/sdk/v3/go/pulumi/config"
)

func Load(ctx *pulumi.Context) *model.ClusterSpec {
	cfg := pulumiconfig.New(ctx, "anycloud-k8s")

	var subnetCidrs []string
	cfg.GetObject("subnetCidrs", &subnetCidrs)

	return &model.ClusterSpec{
		Provider:                   cfg.Get("provider"),
		Name:                       cfg.Get("name"),
		Environment:                cfg.Get("environment"),
		Region:                     cfg.Get("region"),
		GcpProject:                 cfg.Get("gcpProject"),
		AzureResourceGroup:         cfg.Get("azureResourceGroup"),
		OciCompartmentId:           cfg.Get("ociCompartmentId"),
		ProxmoxNodeName:            cfg.Get("proxmoxNodeName"),
		ProxmoxTemplateVmId:        cfg.GetInt("proxmoxTemplateVmId"),
		ProxmoxDatastoreId:         cfg.Get("proxmoxDatastoreId"),
		ProxmoxNetworkBridge:       cfg.Get("proxmoxNetworkBridge"),
		VpcCidr:                    cfg.Get("vpcCidr"),
		SubnetCidrs:                subnetCidrs,
		SSHUser:                    cfg.Get("sshUser"),
		MasterInstanceType:         cfg.Get("masterInstanceType"),
		WorkerInstanceType:         cfg.Get("workerInstanceType"),
		MasterCount:                cfg.GetInt("masterCount"),
		WorkerCount:                cfg.GetInt("workerCount"),
		KubernetesVersion:          cfg.Get("kubernetesVersion"),
		PodCidr:                    cfg.Get("podCidr"),
		ServiceCidr:                cfg.Get("serviceCidr"),
		JoinToken:                  cfg.Get("joinToken"),
		EnableIngress:              cfg.GetBool("enableIngress"),
		EnableGpuOperator:          cfg.GetBool("enableGpuOperator"),
		OpenstackImageName:         cfg.Get("openstackImageName"),
		OpenstackFlavorName:        cfg.Get("openstackFlavorName"),
		OpenstackExternalNetworkId: cfg.Get("openstackExternalNetworkId"),
		OpenstackFloatingIpPool:    cfg.Get("openstackFloatingIpPool"),
		Database: model.DatabaseSpec{
			Enabled:            cfg.GetBool("dbEnabled"),
			Name:               cfg.Get("dbName"),
			Username:           cfg.Get("dbUsername"),
			Password:           cfg.Get("dbPassword"),
			InstanceClass:      cfg.Get("dbInstanceClass"),
			AllocatedStorageGb: cfg.GetInt("dbAllocatedStorageGb"),
			PubliclyAccessible: cfg.GetBool("dbPubliclyAccessible"),
		},

		// Spot + custom OS image.
		// Backend (VmClusterProviderImpl) 가 VmClusterSpec.useSpot / .osImage 를 본 키로 매핑.
		UseSpot: cfg.GetBool("useSpot"),
		OsImage: cfg.Get("osImage"),
		// 노드 root 디스크 크기(GB). 미설정/0 → model.defaults 가 provider 별 50GB 적용.
		RootDiskSizeGb: cfg.GetInt("rootDiskSizeGb"),
	}
}
