// Azure 의 instance 생성은 4-step:
//  1. PublicIp (static)
//  2. NetworkInterface
//  3. NIC-NSG 연결 (NSG 는 VNet 단위가 아니라 NIC/Subnet 단위)
//  4. LinuxVirtualMachine + system-assigned identity 로 RBAC Contributor
//
// Spot 매핑: Priority="Spot" + EvictionPolicy="Deallocate". master 는 NodeSpecsFor 가 UseSpot=false
// 보장. OsImage override 형식: "Publisher:Offer:Sku:Version" (예: "Canonical:ubuntu-24_04-lts:server:latest").
package azure

import (
	"encoding/base64"
	"fmt"
	"strings"

	"anycloud/infra/pulumi/pkg/model"
	"anycloud/infra/pulumi/pkg/provisioner"
	"github.com/pulumi/pulumi-azure/sdk/v6/go/azure/authorization"
	"github.com/pulumi/pulumi-azure/sdk/v6/go/azure/compute"
	"github.com/pulumi/pulumi-azure/sdk/v6/go/azure/network"
	"github.com/pulumi/pulumi/sdk/v3/go/pulumi"
)

// AzureImageRef — SourceImageReference 의 4-tuple. 기본 Ubuntu 24.04 LTS.
type AzureImageRef struct {
	Publisher, Offer, Sku, Version string
}

func defaultImage() AzureImageRef {
	return AzureImageRef{
		Publisher: "Canonical",
		Offer:     "ubuntu-24_04-lts",
		Sku:       "server",
		Version:   "latest",
	}
}

// AzureInstance — interface.InstanceProvisioner 구현.
type AzureInstance struct {
	Location          string
	ResourceGroupName pulumi.StringOutput
	ResourceGroupID   pulumi.IDOutput
	SecurityGroupID   pulumi.IDOutput
	SubnetID          pulumi.IDOutput
	SshPublicKey      pulumi.StringOutput
	SshUser           string
	DefaultImage      AzureImageRef
}

// Provision — NodeSpec → (PublicIp, NIC, NIC-NSG assoc, VM, RBAC) 한 묶음.
func (a *AzureInstance) Provision(
	ctx *pulumi.Context,
	spec *model.ClusterSpec,
	_ *provisioner.NetworkOutput,
	node provisioner.NodeSpec,
	opts ...pulumi.ResourceOption,
) (*provisioner.InstanceOutput, error) {
	if a.Location == "" || a.SshUser == "" {
		return nil, fmt.Errorf("AzureInstance: location/sshUser must be set before Provision")
	}

	suffix := fmt.Sprintf("%s-%d", node.Role, node.Index+1)

	publicIp, err := network.NewPublicIp(ctx, resourceName(spec, suffix+"-ip"), &network.PublicIpArgs{
		Name:              pulumi.String(resourceName(spec, suffix+"-ip")),
		Location:          pulumi.String(a.Location),
		ResourceGroupName: a.ResourceGroupName,
		AllocationMethod:  pulumi.String("Static"),
		Sku:               pulumi.String("Standard"),
	})
	if err != nil {
		return nil, err
	}

	nic, err := network.NewNetworkInterface(ctx, resourceName(spec, suffix+"-nic"), &network.NetworkInterfaceArgs{
		Name:              pulumi.String(resourceName(spec, suffix+"-nic")),
		Location:          pulumi.String(a.Location),
		ResourceGroupName: a.ResourceGroupName,
		IpConfigurations: network.NetworkInterfaceIpConfigurationArray{
			network.NetworkInterfaceIpConfigurationArgs{
				Name:                       pulumi.String("internal"),
				SubnetId:                   a.SubnetID.ToStringOutput(),
				PrivateIpAddressAllocation: pulumi.String("Dynamic"),
				PublicIpAddressId:          publicIp.ID(),
			},
		},
	})
	if err != nil {
		return nil, err
	}

	_, err = network.NewNetworkInterfaceSecurityGroupAssociation(ctx,
		resourceName(spec, suffix+"-nic-nsg"),
		&network.NetworkInterfaceSecurityGroupAssociationArgs{
			NetworkInterfaceId:     nic.ID(),
			NetworkSecurityGroupId: a.SecurityGroupID.ToStringOutput(),
		})
	if err != nil {
		return nil, err
	}

	image := a.resolveImage(node.OsImage)

	vmArgs := &compute.LinuxVirtualMachineArgs{
		Name:                          pulumi.String(resourceName(spec, suffix)),
		Location:                      pulumi.String(a.Location),
		ResourceGroupName:             a.ResourceGroupName,
		Size:                          pulumi.String(node.InstanceType),
		AdminUsername:                 pulumi.String(a.SshUser),
		DisablePasswordAuthentication: pulumi.Bool(true),
		NetworkInterfaceIds:           pulumi.StringArray{nic.ID().ToStringOutput()},
		ComputerName:                  pulumi.String(resourceName(spec, suffix)),
		CustomData:                    encodeCloudInit(node.UserData),
		Identity: compute.LinuxVirtualMachineIdentityArgs{
			Type: pulumi.String("SystemAssigned"),
		},
		AdminSshKeys: compute.LinuxVirtualMachineAdminSshKeyArray{
			compute.LinuxVirtualMachineAdminSshKeyArgs{
				Username:  pulumi.String(a.SshUser),
				PublicKey: a.SshPublicKey,
			},
		},
		OsDisk: compute.LinuxVirtualMachineOsDiskArgs{
			Caching:            pulumi.String("ReadWrite"),
			StorageAccountType: pulumi.String("Standard_LRS"),
			// OS 디스크 크기(GB). model.defaults 가 0 이하를 50 으로 정규화. NodeHasDiskPressure 방지.
			DiskSizeGb: pulumi.Int(node.RootDiskSizeGb),
		},
		SourceImageReference: compute.LinuxVirtualMachineSourceImageReferenceArgs{
			Publisher: pulumi.String(image.Publisher),
			Offer:     pulumi.String(image.Offer),
			Sku:       pulumi.String(image.Sku),
			Version:   pulumi.String(image.Version),
		},
	}

	// Spot priority VM. master 는 NodeSpecsFor 가 UseSpot=false 강제.
	if node.UseSpot {
		vmArgs.Priority = pulumi.String("Spot")
		vmArgs.EvictionPolicy = pulumi.String("Deallocate")
	}

	vm, err := compute.NewLinuxVirtualMachine(ctx, resourceName(spec, suffix), vmArgs, opts...)
	if err != nil {
		return nil, err
	}

	// system-assigned managed identity 에 RG Contributor 부여 — Kubernetes cloud-controller-manager
	// (azure provider) 가 LB / PV / Route 등을 동적 프로비저닝하기 위해 필요.
	_, err = authorization.NewAssignment(ctx, resourceName(spec, suffix+"-rbac"), &authorization.AssignmentArgs{
		Scope:              a.ResourceGroupID.ToStringOutput(),
		RoleDefinitionName: pulumi.String("Contributor"),
		PrincipalId:        vm.Identity.PrincipalId().Elem(),
	})
	if err != nil {
		return nil, err
	}

	return &provisioner.InstanceOutput{
		Resource:   vm,
		InstanceID: vm.ID(),
		PrivateIP:  nic.PrivateIpAddress,
		PublicIP:   publicIp.IpAddress,
	}, nil
}

// resolveImage — node.OsImage 가 "Publisher:Offer:Sku:Version" 형식이면 그대로 사용. 형식이 깨졌거나
// 비었으면 DefaultImage. 잘못된 형식은 silent fallback — input validation 은 backend 책임.
func (a *AzureInstance) resolveImage(override string) AzureImageRef {
	if override == "" {
		return a.DefaultImage
	}
	parts := strings.Split(override, ":")
	if len(parts) != 4 {
		return a.DefaultImage
	}
	return AzureImageRef{Publisher: parts[0], Offer: parts[1], Sku: parts[2], Version: parts[3]}
}

func encodeCloudInit(input pulumi.StringInput) pulumi.StringOutput {
	if input == nil {
		return pulumi.String("").ToStringOutput()
	}
	return input.ToStringOutput().ApplyT(func(value string) string {
		return base64.StdEncoding.EncodeToString([]byte(value))
	}).(pulumi.StringOutput)
}
