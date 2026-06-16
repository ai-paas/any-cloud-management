// Package proxmox implements the Proxmox VE (qemu VM / linux bridge) provisioner.
//
// Orchestrator: 사전 검증 → ProxmoxNetwork (IP layout) → SSH key → cloud-init snippet 2개 (master /
// worker) → ProxmoxInstance loop. 단일 master_userdata 를 모든 master VM 이 공유 (lead 가 init,
// 나머지는 join 분기를 BootstrapService 가 runtime 에 처리).
package proxmox

import (
	"fmt"

	"anycloud/infra/pulumi/pkg/model"
	"anycloud/infra/pulumi/pkg/provisioner"
	"anycloud/infra/pulumi/pkg/userdata"
	"github.com/muhlba91/pulumi-proxmoxve/sdk/v7/go/proxmoxve/storage"
	"github.com/pulumi/pulumi-tls/sdk/v5/go/tls"
	"github.com/pulumi/pulumi/sdk/v3/go/pulumi"
)

type Provisioner struct{}

func New() *Provisioner {
	return &Provisioner{}
}

func (p *Provisioner) Provision(ctx *pulumi.Context, spec *model.ClusterSpec) (pulumi.Map, error) {
	spec = model.ApplyProviderDefaults(spec)
	if spec.ProxmoxNodeName == "" || spec.ProxmoxTemplateVmId == 0 ||
		spec.ProxmoxDatastoreId == "" || spec.ProxmoxNetworkBridge == "" {
		return nil, fmt.Errorf("proxmoxNodeName, proxmoxTemplateVmId, proxmoxDatastoreId, proxmoxNetworkBridge are required")
	}

	// 1) Network — IP layout 계산 (자원 생성 없음).
	module := NewModule()
	_, err := module.Network().Provision(ctx, spec)
	if err != nil {
		return nil, err
	}

	// 2) SSH keypair.
	privateKey, err := tls.NewPrivateKey(ctx, resourceName(spec, "ssh-key"), &tls.PrivateKeyArgs{
		Algorithm: pulumi.String("RSA"),
		RsaBits:   pulumi.Int(4096),
	})
	if err != nil {
		return nil, err
	}

	// 3) Cloud-init snippet 2개 — master 공유, worker 공유.
	masterSnippet, err := storage.NewFile(ctx, resourceName(spec, "master-userdata"), &storage.FileArgs{
		ContentType: pulumi.String("snippets"),
		DatastoreId: pulumi.String(spec.ProxmoxDatastoreId),
		NodeName:    pulumi.String(spec.ProxmoxNodeName),
		SourceRaw: &storage.FileSourceRawArgs{
			Data:     userdata.Master(spec),
			FileName: pulumi.String(resourceName(spec, "master.cloud-config.yaml")),
		},
	})
	if err != nil {
		return nil, err
	}
	workerSnippet, err := storage.NewFile(ctx, resourceName(spec, "worker-userdata"), &storage.FileArgs{
		ContentType: pulumi.String("snippets"),
		DatastoreId: pulumi.String(spec.ProxmoxDatastoreId),
		NodeName:    pulumi.String(spec.ProxmoxNodeName),
		SourceRaw: &storage.FileSourceRawArgs{
			Data:     userdata.Worker(spec),
			FileName: pulumi.String(resourceName(spec, "worker.cloud-config.yaml")),
		},
	})
	if err != nil {
		return nil, err
	}

	module.SetInstanceContext(
		spec.ProxmoxNodeName, spec.ProxmoxTemplateVmId,
		spec.ProxmoxDatastoreId, spec.ProxmoxNetworkBridge,
		spec.SSHUser, privateKey.PublicKeyOpenssh,
		masterSnippet.ID().ToStringOutput(), workerSnippet.ID().ToStringOutput())

	// 4) Instance loop — UserData 는 이미 Storage.File 로 갖고 있으므로 NodeSpec.UserData 는 unused.
	nodeSpecs := provisioner.NodeSpecsFor(spec)

	var leadMaster *provisioner.InstanceOutput
	masterOuts := make([]*provisioner.InstanceOutput, 0, spec.MasterCount)
	for _, n := range nodeSpecs {
		if n.Role != provisioner.RoleMaster {
			continue
		}
		out, perr := module.Instance().Provision(ctx, spec, nil, n)
		if perr != nil {
			return nil, perr
		}
		masterOuts = append(masterOuts, out)
		if leadMaster == nil {
			leadMaster = out
		}
	}
	if leadMaster == nil {
		return nil, fmt.Errorf("no master NodeSpec produced — masterCount=%d", spec.MasterCount)
	}

	workerOuts := make([]*provisioner.InstanceOutput, 0, spec.WorkerCount)
	for _, n := range nodeSpecs {
		if n.Role != provisioner.RoleWorker {
			continue
		}
		out, perr := module.Instance().Provision(ctx, spec, nil, n,
			pulumi.DependsOn([]pulumi.Resource{leadMaster.Resource}))
		if perr != nil {
			return nil, perr
		}
		workerOuts = append(workerOuts, out)
	}

	leadMasterIP := module.network.MasterIPs[0]

	// 5) Outputs — backend 호환 키 셋 그대로.
	outputs := pulumi.Map{
		"provider":         pulumi.String(model.CanonicalProviderName(spec.Provider)),
		"clusterName":      pulumi.String(spec.Name),
		"masterVmSpec":     pulumi.String(spec.MasterInstanceType),
		"workerVmSpec":     pulumi.String(spec.WorkerInstanceType),
		"osImage":          pulumi.String(model.ResolvedOsImage(spec)),
		"vpcId":            pulumi.String(spec.ProxmoxNodeName),
		"subnetId":         pulumi.String(spec.ProxmoxNetworkBridge),
		"masterInstanceId": leadMaster.InstanceID,
		"masterPublicIp":   pulumi.String(""),
		"masterPrivateIp":  pulumi.String(leadMasterIP),
		"masterPublicDns":  pulumi.String(""),
		"apiServerUrl":     pulumi.Sprintf("https://%s:6443", leadMasterIP),
		"sshPrivateKeyPem": pulumi.ToSecret(privateKey.PrivateKeyPem),
		"masterSshCommand": pulumi.ToSecret(pulumi.Sprintf(
			"ssh -i ./secrets/%s.pem %s@%s",
			spec.Name, spec.SSHUser, leadMasterIP,
		)),
		"kubeconfigRemotePath": pulumi.String("/etc/kubernetes/admin.conf"),
		"kubeconfigFetchCommand": pulumi.ToSecret(pulumi.Sprintf(
			"ssh -i ./secrets/%s.pem %s@%s \"sudo cat /etc/kubernetes/admin.conf\" > ./kubeconfig-%s",
			spec.Name, spec.SSHUser, leadMasterIP, spec.Name,
		)),
		"nodes": buildNodeArray(spec, module.network.MasterIPs, module.network.WorkerIPs,
			masterOuts, workerOuts),
	}

	return outputs, nil
}

// buildNodeArray — proxmox 의 node 는 name 필드 추가 (HA: master-1, master-2 구분).
func buildNodeArray(spec *model.ClusterSpec,
	masterIPs, workerIPs []string,
	masters, workers []*provisioner.InstanceOutput) pulumi.Array {
	nodes := pulumi.Array{}
	for i, m := range masters {
		nodes = append(nodes, pulumi.Map{
			"role":       pulumi.String("master"),
			"name":       pulumi.String(fmt.Sprintf("master-%d", i+1)),
			"instanceId": m.InstanceID,
			"publicIp":   pulumi.String(""),
			"privateIp":  pulumi.String(masterIPs[i]),
			"publicDns":  pulumi.String(""),
			"ssh": pulumi.ToSecret(pulumi.Sprintf(
				"ssh -i ./secrets/%s.pem %s@%s",
				spec.Name, spec.SSHUser, masterIPs[i],
			)),
		})
	}
	for i, w := range workers {
		nodes = append(nodes, pulumi.Map{
			"role":       pulumi.String(fmt.Sprintf("worker-%d", i+1)),
			"instanceId": w.InstanceID,
			"publicIp":   pulumi.String(""),
			"privateIp":  pulumi.String(workerIPs[i]),
			"publicDns":  pulumi.String(""),
			"ssh": pulumi.Sprintf(
				"ssh -i ./secrets/%s.pem %s@%s",
				spec.Name, spec.SSHUser, workerIPs[i],
			),
		})
	}
	return nodes
}

func resourceName(spec *model.ClusterSpec, suffix string) string {
	return model.JoinResourceName(spec.Name, suffix)
}
