// Package gcp implements the Google Cloud (Compute Engine / VPC / Service Account) provisioner.
//
// AWS sample (STAGE 2) 와 동일한 패턴: Network/Instance 의 책임 분리 → orchestrator (본 파일) 가
// NodeSpecsFor 로 master/worker 의 정규화 spec list 를 만들고 InstanceProvisioner 에 일괄 위임.
//
// GCP 특화 setup (image lookup, service account, SSH key, master static IP) 은 본 파일에 남는다 —
// 다른 provider 와 공유되는 추상이 아님.
package gcp

import (
	"fmt"
	"strings"

	"anycloud/infra/pulumi/pkg/model"
	"anycloud/infra/pulumi/pkg/provisioner"
	"anycloud/infra/pulumi/pkg/userdata"
	"github.com/pulumi/pulumi-gcp/sdk/v8/go/gcp/compute"
	"github.com/pulumi/pulumi-gcp/sdk/v8/go/gcp/serviceaccount"
	"github.com/pulumi/pulumi-tls/sdk/v5/go/tls"
	"github.com/pulumi/pulumi/sdk/v3/go/pulumi"
)

type Provisioner struct{}

func New() *Provisioner {
	return &Provisioner{}
}

func (p *Provisioner) Provision(ctx *pulumi.Context, spec *model.ClusterSpec) (pulumi.Map, error) {
	spec = model.ApplyProviderDefaults(spec)

	if spec.GcpProject == "" {
		return nil, fmt.Errorf("gcpProject is required for GCP provisioning")
	}
	if spec.Region == "" {
		return nil, fmt.Errorf("region is required for GCP provisioning")
	}

	zones, err := compute.GetZones(ctx, &compute.GetZonesArgs{
		Region: stringPtr(spec.Region),
		Status: stringPtr("UP"),
	}, nil)
	if err != nil {
		return nil, err
	}
	if len(zones.Names) < 2 {
		return nil, fmt.Errorf("at least two active zones are required in region %q", spec.Region)
	}

	// 1) Network — VPC/subnet/firewall.
	module := NewModule()
	_, err = module.Network().Provision(ctx, spec)
	if err != nil {
		return nil, err
	}

	// 2) GCP-only setup — image lookup / SSH key / service account.
	imageSelfLink, err := resolveImage(ctx, spec)
	if err != nil {
		return nil, err
	}

	privateKey, err := tls.NewPrivateKey(ctx, resourceName(spec, "ssh-key"), &tls.PrivateKeyArgs{
		Algorithm: pulumi.String("RSA"),
		RsaBits:   pulumi.Int(4096),
	})
	if err != nil {
		return nil, err
	}

	account, err := serviceaccount.NewAccount(ctx, resourceName(spec, "sa"), &serviceaccount.AccountArgs{
		AccountId:   pulumi.String(trimAccountID(resourceName(spec, "sa"))),
		DisplayName: pulumi.String(spec.Name + " vm cluster service account"),
		Project:     pulumi.String(spec.GcpProject),
	})
	if err != nil {
		return nil, err
	}

	module.SetInstanceContext(
		spec.GcpProject, spec.Region, zones.Names,
		imageSelfLink, account, privateKey.PublicKeyOpenssh)

	// 3) Instance loop — NodeSpecsFor → role 별 UserData fill → GcpInstance.Provision.
	nodeSpecs := provisioner.NodeSpecsFor(spec)
	for i := range nodeSpecs {
		if nodeSpecs[i].Role == provisioner.RoleMaster {
			nodeSpecs[i].UserData = userdata.Master(spec)
		} else {
			nodeSpecs[i].UserData = userdata.Worker(spec)
		}
	}

	var masterOut *provisioner.InstanceOutput
	workerOuts := make([]*provisioner.InstanceOutput, 0, spec.WorkerCount)
	for _, n := range nodeSpecs {
		if n.Role != provisioner.RoleMaster {
			continue
		}
		out, perr := module.Instance().Provision(ctx, spec, nil, n)
		if perr != nil {
			return nil, perr
		}
		masterOut = out
	}
	if masterOut == nil {
		return nil, fmt.Errorf("no master NodeSpec produced — masterCount=%d", spec.MasterCount)
	}

	for _, n := range nodeSpecs {
		if n.Role != provisioner.RoleWorker {
			continue
		}
		out, perr := module.Instance().Provision(ctx, spec, nil, n,
			pulumi.DependsOn([]pulumi.Resource{masterOut.Resource}))
		if perr != nil {
			return nil, perr
		}
		workerOuts = append(workerOuts, out)
	}

	// 4) Outputs — backend 가 의존하는 기존 key 셋 그대로.
	outputs := pulumi.Map{
		"provider":         pulumi.String(model.CanonicalProviderName(spec.Provider)),
		"clusterName":      pulumi.String(spec.Name),
		"masterVmSpec":     pulumi.String(spec.MasterInstanceType),
		"workerVmSpec":     pulumi.String(spec.WorkerInstanceType),
		"osImage":          pulumi.String(model.ResolvedOsImage(spec)),
		"networkId":        module.network.Network.ID(),
		"subnetId":         module.network.Subnetwork.ID(),
		"masterInstanceId": masterOut.InstanceID,
		"masterPublicIp":   masterOut.PublicIP,
		"masterPrivateIp":  masterOut.PrivateIP,
		"masterPublicDns":  pulumi.String(""),
		"apiServerUrl":     pulumi.Sprintf("https://%s:6443", masterOut.PublicIP),
		"sshPrivateKeyPem": pulumi.ToSecret(privateKey.PrivateKeyPem),
		"masterSshCommand": pulumi.Sprintf(
			"ssh -i ./secrets/%s.pem %s@%s",
			spec.Name, spec.SSHUser, masterOut.PublicIP,
		),
		"kubeconfigRemotePath": pulumi.String("/etc/kubernetes/admin.conf"),
		"kubeconfigFetchCommand": pulumi.Sprintf(
			"ssh -i ./secrets/%s.pem %s@%s \"sudo cat /etc/kubernetes/admin.conf\" > ./kubeconfig-%s",
			spec.Name, spec.SSHUser, masterOut.PublicIP, spec.Name,
		),
		"nodes": buildNodeArray(spec, masterOut, workerOuts),
	}

	return outputs, nil
}

// resolveImage — osImage override (예: "projects/<proj>/global/images/<name>") 또는
// default Ubuntu 24.04 LTS family lookup.
func resolveImage(ctx *pulumi.Context, spec *model.ClusterSpec) (string, error) {
	if spec.OsImage != "" {
		return spec.OsImage, nil
	}
	image, err := compute.LookupImage(ctx, &compute.LookupImageArgs{
		Family:  stringPtr("ubuntu-2404-lts-amd64"),
		Project: stringPtr("ubuntu-os-cloud"),
	}, nil)
	if err != nil {
		return "", err
	}
	return image.SelfLink, nil
}

// buildNodeArray — backend 출력 호환 (role / instanceId / publicIp / privateIp / publicDns / ssh).
func buildNodeArray(spec *model.ClusterSpec, master *provisioner.InstanceOutput,
	workers []*provisioner.InstanceOutput) pulumi.Array {
	nodes := pulumi.Array{
		pulumi.Map{
			"role":       pulumi.String("master"),
			"instanceId": master.InstanceID,
			"publicIp":   master.PublicIP,
			"privateIp":  master.PrivateIP,
			"publicDns":  pulumi.String(""),
			"ssh": pulumi.Sprintf(
				"ssh -i ./secrets/%s.pem %s@%s",
				spec.Name, spec.SSHUser, master.PublicIP,
			),
		},
	}

	for i, worker := range workers {
		nodes = append(nodes, pulumi.Map{
			"role":       pulumi.String(fmt.Sprintf("worker-%d", i+1)),
			"instanceId": worker.InstanceID,
			"publicIp":   worker.PublicIP,
			"privateIp":  worker.PrivateIP,
			"publicDns":  pulumi.String(""),
			"ssh": pulumi.Sprintf(
				"ssh -i ./secrets/%s.pem %s@%s",
				spec.Name, spec.SSHUser, worker.PublicIP,
			),
		})
	}

	return nodes
}

func resourceName(spec *model.ClusterSpec, suffix string) string {
	return fmt.Sprintf("%s-%s", spec.Name, suffix)
}

func sanitizeName(value string) string {
	normalized := strings.ToLower(value)
	normalized = strings.ReplaceAll(normalized, "_", "-")
	return normalized
}

func trimAccountID(value string) string {
	accountID := sanitizeName(value)
	if len(accountID) > 30 {
		accountID = accountID[:30]
	}
	return strings.Trim(accountID, "-")
}

func stringPtr(value string) *string {
	return &value
}
