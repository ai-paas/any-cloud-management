# Pulumi E2E Testing — LocalStack (AWS) sandbox

infra/pulumi 의 multi-CSP 프로비저닝 흐름을 **실 cloud 비용 없이** 회귀 검증. LocalStack 가 AWS API
를 mock — Pulumi 의 `aws.ec2.Instance`, `aws.ec2.Vpc` 등이 LocalStack endpoint 로 routing.

## 1. 적용 범위 — AWS 만 우선

LocalStack 의 free tier 가 EC2 / VPC / IAM / S3 / RDS / SSM 등 핵심 AWS 자원 mock. 다른 CSP 의
mock:

| CSP | Mock 도구 | 성숙도 | 비용 |
|---|---|---|---|
| **AWS** | LocalStack (free) | ✓ 매우 좋음 | $0 (free), $35/mo (pro) |
| Azure | Azurite (Storage 만) | △ Storage 외 mock 부족 | $0 |
| GCP | gcloud emulator (일부) | △ Compute API 미지원 | $0 |
| OCI | (없음) | ✗ | sandbox account 필요 |
| Alibaba | (없음) | ✗ | sandbox account 필요 |
| OpenStack | DevStack 컨테이너 | △ 복잡 | infra cost |
| Proxmox | mock 없음 — VM 안 | ✗ | sandbox VM |
| DigitalOcean | mock 없음 | ✗ | sandbox account |

→ **현실적 접근: AWS 만 LocalStack 자동, 다른 CSP 는 sandbox account credential 명시 시점에 manual e2e**.

## 2. LocalStack docker-compose

```yaml
# docker-compose.pulumi-e2e.yml (신규)
services:
  localstack:
    image: localstack/localstack:3.5.0
    ports:
      - "4566:4566"
    environment:
      SERVICES: "ec2,vpc,iam,sts,s3,ssm,route53"
      DEBUG: "0"
      DOCKER_HOST: "unix:///var/run/docker.sock"
    volumes:
      - "/var/run/docker.sock:/var/run/docker.sock"
```

## 3. Pulumi config 의 endpoint override

```typescript
// infra/pulumi 의 AwsProvider 생성 시 (PoC)
const awsProvider = new aws.Provider("local", {
    accessKey: "test",
    secretKey: "test",
    region: "us-east-1",
    skipCredentialsValidation: true,
    skipRequestingAccountId: true,
    endpoints: [{
        ec2: "http://localhost:4566",
        vpc: "http://localhost:4566",
        iam: "http://localhost:4566",
        sts: "http://localhost:4566",
    }],
});
```

→ anycloud Pulumi program 의 `pkg/providers/aws/aws.go` 에 **environment 별 endpoint override
지원** 추가. dev/test 환경에서 `PULUMI_AWS_ENDPOINT=http://localhost:4566` env 인식.

## 4. JUnit 통합 (제안)

`apps/anycloud/src/test/java/.../provisioning/PulumiAwsE2EIntegrationTest.java`:

```java
@SpringBootTest
@Testcontainers
class PulumiAwsE2EIntegrationTest {

    @Container
    static final LocalStackContainer localstack = new LocalStackContainer(
            DockerImageName.parse("localstack/localstack:3.5.0"))
        .withServices(Service.EC2, Service.IAM, Service.STS);

    @DynamicPropertySource
    static void localstackProps(DynamicPropertyRegistry registry) {
        registry.add("pulumi.aws.endpoint", localstack::getEndpoint);
    }

    @Test
    void aws_vm_cluster_provision_dry_run() {
        // 1. spec 작성 (master 1 + worker 1, AWS)
        // 2. preflight 통과 검증
        // 3. pulumi preview → mock 의 plan output 분석
        // 4. pulumi up → LocalStack 의 EC2 instance 생성 확인
        // 5. teardown — pulumi destroy
    }
}
```

## 5. Gradle dependency

```gradle
testImplementation 'org.testcontainers:localstack:1.20.1'
```

## 6. CI integration

```yaml
# .github/workflows/ci.yml — go job 다음에 추가
- name: Pulumi AWS E2E
  run: ./gradlew :anycloud:test --tests '*PulumiAwsE2EIntegrationTest'
```

LocalStack container 부팅 ~10-20초 + Pulumi up ~1-2분. CI overhead 적절.

## 7. 한계 + 단계적 적용

LocalStack 가 mock 이라 한계:
- IAM policy evaluation 일부 simulate (실 AWS 와 미세 차이)
- VPC peering / DNS 같은 advanced 기능 부족
- AMI 검색 — fake AMI 만 반환

→ **happy path 회귀만 신뢰**. 보안 / 권한 / production-grade test 는 sandbox AWS account 필수.

단계적 도입:
| Phase | 작업 |
|---|---|
| 1 | docker-compose.pulumi-e2e.yml + LocalStackContainer dependency |
| 2 | AwsProvider 의 endpoint override env 지원 (infra/pulumi 수정) |
| 3 | PoC test — vpc create / instance launch |
| 4 | preflight + provision step 의 end-to-end test |
| 5 | bootstrap step (kubeadm) 는 sandbox AWS 필요 — mock 불가 |

→ Pulumi 자체의 provisioning 흐름은 LocalStack 이 cover, bootstrap (kubeadm) 은 별도.

## 8. 다른 CSP — 향후 옵션

| CSP | 시점 | 방식 |
|---|---|---|
| Azure | Azurite 가 storage 만 — 보류 | sandbox account |
| GCP | gcloud emulator 가 일부 — 보류 | sandbox account |
| OCI / Alibaba / OpenStack | sandbox account / DevStack | manual |

→ AWS 만 LocalStack 자동, 다른 CSP 는 sandbox credential 등록된 PR 의 label trigger 로 manual e2e.
