// Package aws implements the AWS (EC2 / VPC / IAM) provisioner.
//
// Provisioner.Provision 은 외부 ClusterProvisioner contract 를 유지하면서 내부적으로 AwsModule
// (Network/Instance 분리) 을 사용. AWS-only setup (AMI 조회, IAM role, SSH keypair, RDS) 은 본
// 파일에 남기고, 재사용 가능한 부분만 network.go / instance.go / module.go 로 추출됨.
//
// 다른 7개 provider 도 같은 패턴으로 마이그레이션 가능 — interface.go 의 NetworkProvisioner /
// InstanceProvisioner 만 구현하면 됨.
package aws

import (
	"fmt"

	"anycloud/infra/pulumi/pkg/model"
	"anycloud/infra/pulumi/pkg/provisioner"
	"anycloud/infra/pulumi/pkg/userdata"
	"github.com/pulumi/pulumi-aws/sdk/v6/go/aws/ec2"
	"github.com/pulumi/pulumi-aws/sdk/v6/go/aws/iam"
	"github.com/pulumi/pulumi-aws/sdk/v6/go/aws/rds"
	"github.com/pulumi/pulumi-tls/sdk/v5/go/tls"
	"github.com/pulumi/pulumi/sdk/v3/go/pulumi"
)

type Provisioner struct{}

func New() *Provisioner {
	return &Provisioner{}
}

func (p *Provisioner) Provision(ctx *pulumi.Context, spec *model.ClusterSpec) (pulumi.Map, error) {
	spec = model.ApplyProviderDefaults(spec)

	// 1) Network — VPC/subnet/IGW/RT/SG.
	module := NewModule()
	netOut, err := module.Network().Provision(ctx, spec)
	if err != nil {
		return nil, err
	}

	// 2) AWS-only setup — AMI 조회 / SSH keypair / IAM role+profile. 본 자원은 cluster 전체 공유.
	amiId, err := resolveAmi(ctx, spec)
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

	keyPair, err := ec2.NewKeyPair(ctx, resourceName(spec, "keypair"), &ec2.KeyPairArgs{
		KeyName:   pulumi.String(resourceName(spec, "keypair")),
		PublicKey: privateKey.PublicKeyOpenssh,
		Tags:      tags(spec, "keypair"),
	})
	if err != nil {
		return nil, err
	}

	instanceProfile, err := buildInstanceProfile(ctx, spec)
	if err != nil {
		return nil, err
	}

	module.SetInstanceContext(amiId, keyPair, instanceProfile)

	// 3) Instance loop — NodeSpecsFor 가 master/worker 의 정규화 spec list 를 만들고, 그 위에
	//    role 별 UserData 를 채워 AwsInstance.Provision 에 일괄 위임.
	nodeSpecs := provisioner.NodeSpecsFor(spec)
	for i := range nodeSpecs {
		if nodeSpecs[i].Role == provisioner.RoleMaster {
			nodeSpecs[i].UserData = userdata.Master(spec)
		} else {
			nodeSpecs[i].UserData = userdata.Worker(spec)
		}
	}

	// master 먼저 (workers DependsOn master). 본 PoC 는 master 1개 가정 (HA 는 별 sprint).
	var masterOut *provisioner.InstanceOutput
	workerOuts := make([]*provisioner.InstanceOutput, 0, spec.WorkerCount)
	for _, n := range nodeSpecs {
		if n.Role != provisioner.RoleMaster {
			continue
		}
		out, perr := module.Instance().Provision(ctx, spec, netOut, n)
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
		out, perr := module.Instance().Provision(ctx, spec, netOut, n,
			pulumi.DependsOn([]pulumi.Resource{masterOut.Resource}))
		if perr != nil {
			return nil, perr
		}
		workerOuts = append(workerOuts, out)
	}

	// 4) Outputs — kubeconfig fetch command 등 secret 처리는 기존과 동일.
	outputs := pulumi.Map{
		"provider":         pulumi.String(model.CanonicalProviderName(spec.Provider)),
		"clusterName":      pulumi.String(spec.Name),
		"masterVmSpec":     pulumi.String(spec.MasterInstanceType),
		"workerVmSpec":     pulumi.String(spec.WorkerInstanceType),
		"osImage":          pulumi.String(model.ResolvedOsImage(spec)),
		"vpcId":            netOut.VpcID,
		"masterInstanceId": masterOut.InstanceID,
		"masterPublicIp":   masterOut.PublicIP,
		"masterPrivateIp":  masterOut.PrivateIP,
		"masterPublicDns":  masterOut.PublicIP, // STAGE 2 simplification — caller 가 IP 로 DNS 대체
		"apiServerUrl":     pulumi.Sprintf("https://%s:%d", masterOut.PublicIP, model.PortKubernetesAPIServer),
		"sshPrivateKeyPem": pulumi.ToSecret(privateKey.PrivateKeyPem),
		"masterSshCommand": pulumi.ToSecret(pulumi.Sprintf(
			"ssh -i ./secrets/%s.pem %s@%s",
			spec.Name, spec.SSHUser, masterOut.PublicIP,
		)),
		"kubeconfigRemotePath": pulumi.String("/etc/kubernetes/admin.conf"),
		"kubeconfigFetchCommand": pulumi.ToSecret(pulumi.Sprintf(
			"ssh -i ./secrets/%s.pem %s@%s \"sudo cat /etc/kubernetes/admin.conf\" > ./kubeconfig-%s",
			spec.Name, spec.SSHUser, masterOut.PublicIP, spec.Name,
		)),
		"nodes": buildNodeArray(spec, masterOut, workerOuts),
	}

	if spec.Database.Enabled {
		dbOutputs, dbErr := provisionDatabase(ctx, spec, module.network.Vpc, module.network.Subnets, module.network.SecurityGroup)
		if dbErr != nil {
			return nil, dbErr
		}
		for key, value := range dbOutputs {
			outputs[key] = value
		}
	}

	return outputs, nil
}

// resolveAmi — osImage override 또는 default Ubuntu lookup.
func resolveAmi(ctx *pulumi.Context, spec *model.ClusterSpec) (string, error) {
	if spec.OsImage != "" {
		return spec.OsImage, nil
	}
	ubuntuAmi, err := ec2.LookupAmi(ctx, &ec2.LookupAmiArgs{
		MostRecent: pulumi.BoolRef(true),
		Owners:     []string{"099720109477"},
		Filters: []ec2.GetAmiFilter{
			{Name: "name", Values: []string{"ubuntu/images/hvm-ssd-gp3/ubuntu-noble-24.04-amd64-server-*"}},
			{Name: "virtualization-type", Values: []string{"hvm"}},
			{Name: "architecture", Values: []string{"x86_64"}},
		},
	}, nil)
	if err != nil {
		return "", err
	}
	return ubuntuAmi.Id, nil
}

// buildInstanceProfile — EC2 IAM role + 표준 attachments + instance profile.
func buildInstanceProfile(ctx *pulumi.Context, spec *model.ClusterSpec) (*iam.InstanceProfile, error) {
	const assumeRolePolicy = `{
	  "Version": "2012-10-17",
	  "Statement": [{
	    "Action": "sts:AssumeRole",
	    "Principal": { "Service": "ec2.amazonaws.com" },
	    "Effect": "Allow"
	  }]
	}`

	role, err := iam.NewRole(ctx, resourceName(spec, "ec2-role"), &iam.RoleArgs{
		AssumeRolePolicy: pulumi.String(assumeRolePolicy),
		Tags:             tags(spec, "ec2-role"),
	})
	if err != nil {
		return nil, err
	}

	for i, arn := range []string{
		"arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore",
		"arn:aws:iam::aws:policy/AmazonEC2ReadOnlyAccess",
	} {
		_, attachErr := iam.NewRolePolicyAttachment(ctx,
			resourceName(spec, fmt.Sprintf("role-attach-%d", i+1)),
			&iam.RolePolicyAttachmentArgs{
				Role:      role.Name,
				PolicyArn: pulumi.String(arn),
			})
		if attachErr != nil {
			return nil, attachErr
		}
	}

	return iam.NewInstanceProfile(ctx, resourceName(spec, "ec2-profile"), &iam.InstanceProfileArgs{
		Role: role.Name,
		Tags: tags(spec, "ec2-profile"),
	})
}

// L4: capacity pre-allocation (1 master + N workers). H8: ssh 명령은 secret wrap.
func buildNodeArray(spec *model.ClusterSpec, master *provisioner.InstanceOutput,
	workers []*provisioner.InstanceOutput) pulumi.Array {
	nodes := make(pulumi.Array, 0, 1+len(workers))
	nodes = append(nodes, pulumi.Map{
		"role":       pulumi.String("master"),
		"instanceId": master.InstanceID,
		"publicIp":   master.PublicIP,
		"privateIp":  master.PrivateIP,
		"publicDns":  master.PublicIP, // STAGE 2 simplification — abstraction 에 publicDns 미포함
		"ssh": pulumi.ToSecret(pulumi.Sprintf(
			"ssh -i ./secrets/%s.pem %s@%s",
			spec.Name, spec.SSHUser, master.PublicIP,
		)),
	})

	for i, worker := range workers {
		nodes = append(nodes, pulumi.Map{
			"role":       pulumi.String(fmt.Sprintf("worker-%d", i+1)),
			"instanceId": worker.InstanceID,
			"publicIp":   worker.PublicIP,
			"privateIp":  worker.PrivateIP,
			"publicDns":  worker.PublicIP,
			"ssh": pulumi.ToSecret(pulumi.Sprintf(
				"ssh -i ./secrets/%s.pem %s@%s",
				spec.Name, spec.SSHUser, worker.PublicIP,
			)),
		})
	}

	return nodes
}

func provisionDatabase(
	ctx *pulumi.Context,
	spec *model.ClusterSpec,
	vpc *ec2.Vpc,
	subnets []*ec2.Subnet,
	nodeSecurityGroup *ec2.SecurityGroup,
) (pulumi.Map, error) {
	dbSecurityGroup, err := ec2.NewSecurityGroup(ctx, resourceName(spec, "db-sg"), &ec2.SecurityGroupArgs{
		VpcId:       vpc.ID(),
		Description: pulumi.String("postgresql security group"),
		Ingress: ec2.SecurityGroupIngressArray{
			ec2.SecurityGroupIngressArgs{
				Protocol:       pulumi.String("tcp"),
				FromPort:       pulumi.Int(5432),
				ToPort:         pulumi.Int(5432),
				SecurityGroups: pulumi.StringArray{nodeSecurityGroup.ID().ToStringOutput()},
				Description:    pulumi.String("allow postgres from k8s nodes"),
			},
		},
		Egress: ec2.SecurityGroupEgressArray{
			ec2.SecurityGroupEgressArgs{
				Protocol:    pulumi.String("-1"),
				FromPort:    pulumi.Int(0),
				ToPort:      pulumi.Int(0),
				CidrBlocks:  pulumi.StringArray{pulumi.String("0.0.0.0/0")},
				Description: pulumi.String("all outbound"),
			},
		},
		Tags: tags(spec, "db-sg"),
	})
	if err != nil {
		return nil, err
	}

	subnetIds := pulumi.StringArray{}
	for _, subnet := range subnets {
		subnetIds = append(subnetIds, subnet.ID().ToStringOutput())
	}

	dbSubnetGroup, err := rds.NewSubnetGroup(ctx, resourceName(spec, "db-subnets"), &rds.SubnetGroupArgs{
		SubnetIds: subnetIds,
		Tags:      tags(spec, "db-subnets"),
	})
	if err != nil {
		return nil, err
	}

	instance, err := rds.NewInstance(ctx, resourceName(spec, "postgres"), &rds.InstanceArgs{
		AllocatedStorage:    pulumi.Int(spec.Database.AllocatedStorageGb),
		ApplyImmediately:    pulumi.Bool(true),
		DbName:              pulumi.String(spec.Database.Name),
		Engine:              pulumi.String("postgres"),
		EngineVersion:       pulumi.String("16.4"),
		InstanceClass:       pulumi.String(spec.Database.InstanceClass),
		Username:            pulumi.String(spec.Database.Username),
		Password:            pulumi.String(spec.Database.Password),
		DbSubnetGroupName:   dbSubnetGroup.Name,
		VpcSecurityGroupIds: pulumi.StringArray{dbSecurityGroup.ID().ToStringOutput()},
		PubliclyAccessible:  pulumi.Bool(spec.Database.PubliclyAccessible),
		SkipFinalSnapshot:   pulumi.Bool(true),
		DeletionProtection:  pulumi.Bool(false),
		StorageEncrypted:    pulumi.Bool(true),
		Tags:                tags(spec, "postgres"),
	})
	if err != nil {
		return nil, err
	}

	return pulumi.Map{
		"dbEndpoint": pulumi.Sprintf("%s:%d", instance.Address, instance.Port),
		"dbName":     pulumi.String(spec.Database.Name),
		"dbUsername": pulumi.String(spec.Database.Username),
	}, nil
}

// L7: 모든 provider 가 공유하는 sanitize. AWS naming rule (영숫자 + hyphen) 준수.
func resourceName(spec *model.ClusterSpec, suffix string) string {
	return model.JoinResourceName(spec.Name, suffix)
}

func tags(spec *model.ClusterSpec, component string) pulumi.StringMap {
	return pulumi.StringMap{
		"Name":        pulumi.String(resourceName(spec, component)),
		"Project":     pulumi.String("anycloud"),
		"Environment": pulumi.String(spec.Environment),
		"Cluster":     pulumi.String(spec.Name),
		"ManagedBy":   pulumi.String("pulumi"),
		"Component":   pulumi.String(component),
	}
}
