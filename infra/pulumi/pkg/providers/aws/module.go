// AwsModule 은 Network / Instance / Security 책임의 AWS 구현을 한 객체로 묶는다. 다른 7개 provider
// 마이그레이션이 끝나면 factory.go 가 ProviderModule 만 반환하는 형태로 통합 가능.
//
// 현 단계는 sample — Provisioner.Provision 이 본 모듈을 내부적으로 사용하므로 외부 인터페이스는 그대로.
package aws

import (
	"anycloud/infra/pulumi/pkg/provisioner"
	"github.com/pulumi/pulumi-aws/sdk/v6/go/aws/ec2"
	"github.com/pulumi/pulumi-aws/sdk/v6/go/aws/iam"
)

// AwsModule — interface.ProviderModule 구현 (Security 는 STAGE 3 에서).
type AwsModule struct {
	network  *AwsNetwork
	instance *AwsInstance
}

// NewModule — caller 는 Network().Provision 호출 → SetInstanceContext 로 IAM/keypair/AMI 주입 →
// Instance().Provision loop 의 순서로 사용.
func NewModule() *AwsModule {
	return &AwsModule{
		network:  &AwsNetwork{},
		instance: &AwsInstance{},
	}
}

func (m *AwsModule) Name() string { return "aws" }

func (m *AwsModule) Network() provisioner.NetworkProvisioner { return m.network }

func (m *AwsModule) Instance() provisioner.InstanceProvisioner { return m.instance }

// Security — STAGE 3 (별 PR). 현재는 nil — caller 가 직접 SecurityPolicy 를 변환하지 않으면 안 됨.
// AWS 의 경우 network.go 의 nodeSecurityGroup 이 default policy 를 인라인으로 보유.
func (m *AwsModule) Security() provisioner.SecurityPolicyTranslator { return nil }

// SetInstanceContext — Provisioner.Provision 이 IAM/keypair/AMI 를 만든 뒤 호출. instance loop 가
// 시작되기 전 한 번만.
func (m *AwsModule) SetInstanceContext(ami string, keypair *ec2.KeyPair, instanceProfile *iam.InstanceProfile) {
	m.instance.Ami = ami
	m.instance.KeyPair = keypair
	m.instance.InstanceProfile = instanceProfile
}
