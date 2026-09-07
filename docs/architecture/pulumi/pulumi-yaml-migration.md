# Pulumi 타입 SDK를 생성 YAML로 교체

`cluster-provisioning-spring-boot-starter`가 의존하는 Pulumi Java provider SDK 7종(253MB)을 제거하고,
Java가 생성한 YAML 프로그램을 Pulumi CLI로 실행하는 방식으로 바꾸는 설계입니다.

관련 문서는 [pulumi-multicloud-k8s-blueprint.md](pulumi-multicloud-k8s-blueprint.md),
[pulumi-runtime-with-gateway.md](pulumi-runtime-with-gateway.md),
[pulumi-gpu-support.md](pulumi-gpu-support.md),
[../vmcluster-workflow.md](../vmcluster-workflow.md) 입니다.

## 1. 왜 바꾸는가

이 starter는 **다른 프로젝트가 가져다 쓰는 공개 아티팩트**입니다. 릴리스 태그마다 GHCR Maven으로
publish되고 Apache 2.0 POM을 갖습니다. 그런데 CSP SDK 7종이 `api` 스코프로 선언되어 있어 **가져다 쓰는
모든 프로젝트가 253MB를 그대로 물려받습니다.**

| SDK | 크기 | SDK | 크기 |
|---|---|---|---|
| azure-native | 76.7MB | alicloud | 22.0MB |
| oci | 65.6MB | digitalocean | 3.7MB |
| aws | 46.0MB | openstack | 1.6MB |
| gcp | 37.0MB | **합계** | **252.6MB** |

크기는 bootJar에 실제로 실리는 jar 기준입니다. Gradle 캐시에는 같은 좌표로 `-javadoc`, `-sources`
아티팩트가 함께 있어, 파일 이름만 보고 재면 3배 가까이 부풀려집니다.

OpenStack만 쓰는 사설 배포도 azure-native와 oci를 받습니다. 그리고 이 SDK들은 **public 시그니처에
0건 노출**됩니다 — 순수한 구현 세부사항인데 `api`로 선언되어 소비자의 컴파일 클래스패스까지
오염시킵니다.

Pulumi에서 실제로 일하는 것은 provider 플러그인(Go 바이너리)이고, 엔진이 런타임에 `~/.pulumi/plugins`로
내려받습니다. Java SDK는 생성된 타입 바인딩일 뿐입니다. YAML 프로그램은 `type: aws:ec2:Instance` 처럼
토큰으로 리소스를 참조하므로 바인딩이 필요 없습니다.

## 2. 무엇을 바꾸고 무엇을 유지하는가

| 대상 | 규모 | 처리 |
|---|---|---|
| `program/provisioner/*` | 15파일 2,297줄 | **YAML 생성기로 교체** |
| `program/*` (ClusterSpec, Defaults, KubeadmUserData 등) | 9파일 729줄 | 대부분 유지, 일부 조정 |
| `internal/*` (Automation API 구동, 이벤트, 매핑) | 5파일 670줄 | 실행 경로만 조정 |
| `api/*` (`ProvisioningService`, `ExecutionConfig`) | 6파일 304줄 | **그대로** |
| `autoconfigure/*` | 2파일 197줄 | provisioner bean 등록 정리 |

`KubeadmUserData`는 Pulumi 타입을 하나도 import하지 않는 순수 문자열 템플릿입니다. YAML로 바꿔도
user-data 문자열은 그대로 Java가 만들어 넣습니다.

### 확장점을 닫는다

현재 `ProviderProvisioner`는 public 확장점이고 시그니처가 Pulumi 타입을 노출합니다.

```java
Map<String, Output<?>> provision(Context ctx, ClusterSpec spec);   // com.pulumi.core.Output
```

이 확장점을 **닫습니다.** 유지하려면 YAML 기반으로 시그니처를 재설계해야 하고 설계가 한 겹 늘어납니다.
CSP 지원은 starter가 책임지고, 외부 소비자는 `ProvisioningService`만 씁니다. POM 설명
("7 CSP providers + standard output schema")과도 맞습니다.

외부 소비자가 아직 없을 때 breaking change 비용이 사실상 0입니다. 채택된 뒤에는 메이저 버전과
마이그레이션 안내가 필요합니다.

## 3. 실행 경로 변경

현재는 **inline 프로그램**입니다. `Consumer<Context>`를 넘기면 SDK가 JVM 안에서 실행합니다.

```java
LocalWorkspace.createOrSelectStack(PROJECT_NAME, stackName, programFn, workspaceOpts)
```

바꾼 뒤에는 **local 프로그램**입니다. workDir에 `Pulumi.yaml`을 쓰고 CLI에 맡깁니다.

```java
LocalWorkspace.createOrSelectStack(stackName, workDir, workspaceOpts)
```

`Pulumi.yaml`의 `runtime: yaml`이 CLI에게 YAML 해석을 지시합니다. provider 플러그인은 엔진이 필요할 때
받습니다 — 실제로 쓰는 CSP의 것만 내려옵니다.

### workDir 수명

| 시점 | 동작 |
|---|---|
| provision / preview / refresh | 임시 디렉토리 생성 → `Pulumi.yaml` 기록 → 스택 실행 |
| 종료 | `WorkspaceStack.close()` (기존 try-with-resources 유지) 후 디렉토리 삭제 |
| destroy | 리소스 정의가 필요 없다. 빈 `resources: {}` 프로그램으로 충분 |

현재 destroy는 `Consumer<Context> noopProgram = ctx -> {}` 를 씁니다. YAML에서는 빈 리소스 맵이
같은 역할을 합니다.

**stack 상태는 workDir이 아니라 백엔드(S3/RustFS)에 있습니다.** workDir을 지워도 상태는 남습니다.

### 런타임 요구사항은 바뀌지 않습니다

Pulumi CLI 와 provider 플러그인은 **전환 전후 모두 필수**입니다. Java SDK 는 타입 바인딩일 뿐이고
실제 리소스 CRUD 는 원래부터 플러그인(Go 바이너리)이 했습니다. Automation API 도 inline 이든 local
이든 항상 CLI 프로세스를 띄웁니다.

| 구성 요소 | 타입 SDK (현재) | YAML (전환 후) |
|---|---|---|
| Pulumi CLI | 필수 | 필수 |
| provider 플러그인 | 필수 | 필수 |
| Java SDK jar 253MB | 앱에 포함 | **제거** |

`Dockerfile.pulumi` 가 이미 둘 다 이미지에 넣습니다 — CLI 는 `get.pulumi.com` 에서 받고, 플러그인
8종은 `pulumi plugin install` 로 미리 받아 `PULUMI_PLUGIN_CACHE_DIR=/opt/pulumi-plugins` 에
캐싱합니다. **런타임 다운로드가 없으므로 네트워크가 제한된 환경에서도 동작합니다.**

### 플러그인 버전 고정

타입 SDK 는 jar 이 버전을 담아 자동으로 고정됐습니다. YAML 은 `aws:ec2/instance:Instance` 라는
타입 토큰만 쓰므로, 캐시가 빈 환경에서는 그날의 latest 를 받습니다. 실제로 `tls` 를 5.2.0 대신
5.6.0 으로 받는 것을 확인했습니다. 환경마다 다른 버전이 뜨고, 몇 달 뒤 재현되지 않습니다.

`PulumiProgram.resource` 가 타입 토큰의 패키지를 보고 `options.version` 을 넣습니다. emitter 를
각각 고치지 않아도 되고, 새 emitter 도 자동으로 고정됩니다.

```yaml
master:
  type: aws:ec2/instance:Instance
  properties: { ... }
  options:
    version: 7.44.0
```

값의 출처는 `PULUMI_PLUGINS` 하나입니다. `Dockerfile.pulumi` 가 같은 ARG 로 플러그인을 설치하고
런타임 ENV 로 앱에 넘깁니다. `PluginVersions.DEFAULT` 는 그 ARG 가 없을 때의 대비책이라 같은
값이어야 합니다.

### provider 버전 올릴 때

메이저 상향이라도 우리가 쓰는 것은 VPC, 서브넷, 보안그룹, 인스턴스 같은 코어 리소스라
영향권 밖인 경우가 많습니다. 다만 확인 없이 올리면 안 됩니다. 검증 절차는 이렇습니다.

1. `YamlProgramDumpTest` 로 provider 별 `Pulumi.yaml` 을 생성한다.
2. 생성물에서 실제 쓰는 타입 토큰과 속성 이름을 뽑는다.
3. `pulumi package get-schema <provider>@<새 버전>` 과 대조한다 — 없어진 타입, 없어진 속성,
   새로 생긴 필수 입력, deprecated 사용을 본다.
4. `pulumi preview` 가 자격증명 단계까지 도달하는지 확인한다.

aws 6→7, gcp 8→9, oci 3→4 를 이 절차로 올렸고 타입 38종에서 문제 0건이었습니다.

`pulumi preview` 는 자격증명이 없으면 provider 설정 단계에서 멈춥니다. 속성 이름 오타는 그
뒤에 검사되므로 CLI 만으로는 드러나지 않습니다. 3번의 스키마 대조가 그 자리를 대신합니다.

### 서드파티 provider

`get.pulumi.com` 에 없는 provider 는 취급이 다릅니다.

| provider | 방식 | 설치 |
|---|---|---|
| aws, gcp, azure-native, oci, openstack, tls | Pulumi 플러그인 | `pulumi plugin install` |
| proxmoxve | Pulumi 플러그인 (GitHub 릴리스) | `--server github://api.github.com/muhlba91/pulumi-proxmoxve` |
| ibm | 동적 브리지 패키지 | 플러그인 아님 — `terraform-provider` 베이스가 런타임에 OpenTofu provider 를 붙인다 |

IBM 은 `pulumi plugin install resource ibm` 이 되지 않습니다. YAML 에 `packages:` 선언이
필요하고, 프로비저닝 시점에 `registry.opentofu.org` 에 접근합니다. 사전 캐시 구조가 적용되지
않으므로 폐쇄망에서는 별도 대응이 필요합니다.

### 전부 브리지 방식으로 바꾸지 않는 이유

IBM 처럼 `terraform-provider` 베이스를 공유하면 provider 마다 브리지 런타임을 중복으로 담지
않습니다. 다만 실측해 보면 이득이 크지 않습니다 — `pulumi-resource-aws` 943MB 의 대부분은
브리지가 아니라 terraform-provider-aws 자체(839MB)입니다. 5개 provider 를 다 옮겨도 약 480MB,
22% 입니다.

대가가 훨씬 큽니다. 토큰이 `aws:ec2/instance:Instance` 에서 `aws:index/instance:Instance` 로
바뀌어 emitter 5개를 다시 써야 합니다. Azure 는 더 심합니다 — `azure-native` 는 ARM REST 에서
직접 생성한 native 패키지라 Terraform 대응물이 없고, `azurerm` 으로 가면 리소스 모델이
다릅니다. 프로비저닝 시점의 레지스트리 접근 의존도 생깁니다.

같은 480MB 는 `PULUMI_PLUGINS` 에서 CSP 를 좁혀 훨씬 싸게 얻습니다.

### 플러그인 버전의 단일 출처

`build.gradle` 의 `pulumiAwsVersion` 계열 변수는 SDK 와 함께 제거했습니다. 지우기 전 Dockerfile
쪽 8개 값이 모두 일치하는지 확인했습니다.

`Dockerfile.pulumi` 의 `PULUMI_PLUGINS` 가 단독 출처입니다. 플러그인은 런타임 아티팩트이지 컴파일
의존성이 아니므로 자연스럽습니다.

## 4. YAML 생성 방식

**Java가 문자열을 조립하지 않고 `Map`/`List` 트리를 만들어 SnakeYAML로 직렬화합니다.** 문자열 조립은
따옴표와 들여쓰기를 사람이 관리하게 되어, PromQL이나 user-data처럼 특수문자가 많은 값에서 깨집니다.

```java
Map<String, Object> program = new LinkedHashMap<>();
program.put("name", PROJECT_NAME);
program.put("runtime", "yaml");
program.put("resources", resources);   // LinkedHashMap<String, Map<String,Object>>
program.put("outputs", outputs);
```

`LinkedHashMap`을 쓰는 이유는 진단 때문입니다. 순서가 안정되면 두 스택의 `Pulumi.yaml`을 diff해서
차이를 바로 볼 수 있습니다.

### 타입 SDK 표현 → YAML 표현 대응

| 현재 (Java SDK) | YAML |
|---|---|
| `new Instance("name", args, opts)` | `name: { type: aws:ec2:Instance, properties: {...} }` |
| `vpc.id()` | `${vpc.id}` |
| `publicIp.applyValue(ip -> "https://" + ip + ":6443")` | `https://${master.publicIp}:6443` |
| `.asSecret()` | `fn::secret` |
| `Ec2Functions.getAmi(args)` | `fn::invoke: { function: aws:ec2:getAmi, arguments: {...} }` |
| `opts.dependsOn(x)` | `options: { dependsOn: [${x}] }` |
| worker N개 반복 | Java가 `worker-1`, `worker-2` … 항목을 펼쳐 emit |

YAML에는 반복문도 조건문도 없지만 문제가 되지 않습니다. 반복과 분기는 **YAML을 만드는 Java**가 하고,
YAML은 그 결과가 펼쳐진 평면 그래프입니다.

### `nodes` 출력은 배열로 바꿉니다

기존 provisioner 는 `nodes` 를 **JSON 문자열**로 export 했습니다. Pulumi Java SDK 의 일부 역직렬화
경로가 배열 값을 만나면 깨지기 때문입니다.

YAML 경로는 SDK 를 거치지 않고 CLI 의 `stack output` 을 읽으므로 그 제약이 없습니다. **실제 배열로
내보냅니다.**

계약 불변 원칙에 어긋나 보이지만 아닙니다. 소비자 두 곳이 이미 배열을 기대하거나 양쪽을 처리합니다.

| 소비자 | 배열 | 문자열 |
|---|---|---|
| `ProvisioningResultMapper.nodesList` | 처리 | 처리 |
| `VmClusterNodeResolver.readNodes` | 처리 | 처리 (2026-09-04 수정) |

문자열만 처리하던 쪽은 없었고, `readNodes` 는 **배열만** 처리해서 문자열을 받으면 빈 목록을
돌려줬습니다. `masterHost` 에는 폴백이 있었지만 worker join 목록과 HA `extraMasterHosts` 에는 없어
**worker 가 kubeadm join 을 하지 않는 상태**였습니다. 실제 스택의 `stackOutputs` 로 확인했습니다.

## 5. 출력 계약 보존

이것이 이번 전환의 **성공 기준**입니다. `stackOutputs()`가 돌려주는 키와 의미가 하나도 바뀌면 안 됩니다.

| 키 | 비고 |
|---|---|
| `provider`, `clusterName`, `masterVmSpec`, `workerVmSpec`, `osImage` | 정적 값 |
| `vpcId`, `masterInstanceId`, `masterPublicIp`, `masterPrivateIp`, `masterPublicDns` | 리소스 참조 |
| `apiServerUrl` | `https://${master.publicIp}:6443` 보간 |
| `sshPrivateKeyPem`, `masterSshCommand`, `kubeconfigFetchCommand` | **secret** |
| `kubeconfigRemotePath` | 정적 |
| `nodes` | 배열 (위 참조) |
| CSP별 extras (예: `dbEndpoint`) | provider별 추가 |

anycloud 쪽에서 이 값을 읽는 곳은 `stackOutputs()` 호출 6곳과 `VmClusterNodeResolver`,
`VmClusterPayloadServiceImpl`입니다. 계약이 유지되면 anycloud는 한 줄도 바뀌지 않습니다.

## 6. CSP별 구조

provisioner 7종을 그대로 옮기지 않고 **공통 골격 + CSP별 리소스 정의**로 나눕니다.

| 구성 | 책임 |
|---|---|
| `YamlProgramBuilder` | `Pulumi.yaml` 골격, outputs 조립, 직렬화 |
| `ProviderYamlEmitter` (내부 인터페이스) | CSP별 `resources` 맵 생성 |
| `Aws/Gcp/Azure/... YamlEmitter` | 각 CSP의 네트워크, 보안그룹, 인스턴스 정의 |
| `CommonOutputs` | 출력 키 조립 — 현재 `assembleOutputs` 등가물 |

`ProviderRegistry`는 유지하되 `ProviderProvisioner` 대신 `ProviderYamlEmitter`를 담습니다. 등록
방식(autoconfigure의 bean 7개)도 그대로입니다.

**emitter는 public이 아닙니다.** 확장점을 닫기로 했으므로 package-private 또는 `internal` 하위에 둡니다.

## 7. 이관 전략

**CSP 하나씩, 병행 운영 없이 순차 교체합니다.**

병행 운영(타입 SDK와 YAML을 동시에 두고 플래그로 전환)은 고려했다가 접었습니다. SDK 의존성이 남아
있으면 732MB가 그대로라 목적을 달성하지 못하고, 두 경로의 출력이 미묘하게 달라도 알아채기 어렵습니다.

| 단계 | 내용 | 검증 | 상태 |
|---|---|---|---|
| 1 | `YamlProgramBuilder` + 출력 조립 + OpenStack emitter | OpenStack 스택 실제 생성 | 완료 |
| 2 | AWS emitter | `pulumi preview` 구조 검증 | 완료 |
| 3 | GCP emitter | `pulumi preview` 구조 검증 | 완료 |
| 4 | OCI, Azure emitter | `pulumi preview` 구조 검증 | 완료 |
| 5 | `ProviderProvisioner` 계열 제거, `build.gradle`에서 SDK 제거 | 크기 실측, 전체 회귀 | 완료 |
| 6 | Proxmox emitter | `pulumi preview` 구조 검증 | 완료 |
| 7 | IBM emitter | 각 스택 실제 생성 | 미착수 |

5단계 결과는 bootJar 440.4MB → 187.7MB입니다. `tls` SDK도 함께 걷어냈습니다 — YAML은
`tls:index/privateKey:PrivateKey`를 토큰으로 참조하고 CLI 플러그인이 해석하므로 Java 바인딩이
필요 없습니다. 남는 Pulumi 의존성은 Automation API(`com.pulumi:pulumi`) 3.4MB뿐입니다.

Proxmox 는 다른 CSP 와 모양이 다릅니다. 하이퍼바이저라 VPC, 서브넷, 보안그룹을 만들지 않고
기존 브리지에 붙습니다. `vpcCidr` 과 `subnetCidrs` 는 쓰이지 않습니다. 인스턴스 타입도 없어
`masterInstanceType` 을 `"코어-메모리MiB"` 규약으로 받습니다.

`pulumi package get-schema` 결과와 YAML 엔진이 강제하는 스키마가 달랐습니다. `VmLegacyDisk` 의
경우 get-schema 는 `sizeGb` 를 주는데 엔진은 `size` 와 필수 `interface` 를 요구합니다. 같은 이름의
타입 항목이 둘 있어 조회 순서에 따라 다른 쪽이 잡힙니다. `proxmoxve:index/VmLegacyDisk:VmLegacyDisk`
처럼 전체 키로 조회해야 하고, 최종 확인은 `pulumi preview` 로 해야 합니다.

IBM 은 동적 브리지 패키지라 설치 방식이 다릅니다. emitter 는 아직 없습니다.

실제 스택 생성까지 확인한 것은 OpenStack뿐입니다. 나머지는 자격증명이 없어 `pulumi preview`가
타입 토큰과 참조를 해석하는 지점까지만 확인했고, 속성 이름은 provider 스키마와 대조했습니다.

Alibaba와 DigitalOcean은 대상에서 제외합니다. 목표 CSP는 AWS, GCP, Azure, Oracle Cloud,
OpenStack, Proxmox, IBM입니다. 두 provider의 타입 SDK provisioner는 6단계에서 함께 걷어냅니다.

OpenStack을 먼저 하는 이유는 두 가지입니다. 리소스 정의가 가장 단순하고(1.6MB SDK), 사설 환경이라
검증 비용이 낮습니다.

**5단계 전까지는 크기가 줄지 않습니다.** 중간 단계에서는 두 방식이 공존하므로, 진행 중임을 팀이
알아야 합니다.

## 8. 검증

실제 CSP 없이 어디까지 확인할 수 있는지가 이 작업의 최대 난점입니다.

| 층위 | 방법 | 실제 CSP 필요 |
|---|---|---|
| YAML 생성 | 생성된 `Pulumi.yaml`을 스냅샷과 비교 | 불요 |
| YAML 문법 | `pulumi preview` — CLI가 파싱하고 provider 플러그인이 스키마 검증 | 불요 (자격증명은 필요할 수 있음) |
| 출력 키 목록 | 생성 YAML의 `outputs` 키가 현재 `assembleOutputs`와 동일한지 | 불요 |
| 리소스 그래프 동등성 | 같은 `ClusterSpec`으로 타입 SDK와 YAML의 `preview` 결과를 비교 | 자격증명 필요 |
| 실제 생성 | `pulumi up` 후 kubeadm 부트스트랩까지 | **필요** |

가장 가치 있는 것은 **리소스 그래프 동등성 비교**입니다. 전환 전후로 같은 스펙에 대해 `preview`를
돌려 생성될 리소스 종류와 개수가 같은지 봅니다. 이걸로 "빠뜨린 리소스"를 잡습니다.

스냅샷 테스트는 회귀 방지에 쓰되 정답 판정에는 쓰지 않습니다 — 스냅샷은 현재 구현을 굳힐 뿐 그것이
옳은지는 말해주지 않습니다.

## 9. 위험

| 위험 | 영향 | 대응 |
|---|---|---|
| 타입 안전성 상실 | 속성 오타가 컴파일이 아니라 `up` 시점에 드러남 | 워크플로우가 이미 `preview`를 거친다. preflight에서 실패하도록 배치 |
| 리소스 누락 | 조용히 덜 만들어짐 | preview 그래프 비교로 잡는다 |
| secret 처리 실수 | `sshPrivateKeyPem`이 평문으로 상태에 저장 | `fn::secret` 적용을 출력 단위 테스트로 고정 |
| CSP별 미묘한 차이 | 특정 CSP만 깨짐 | 순차 이관 — 한 번에 하나씩만 위험에 노출 |

`fn::invoke`로 바뀌는 AMI 조회(AWS)와 이미지 조회(GCP, Alibaba, OCI)가 특히 주의 대상입니다. 인자
구조가 타입 SDK와 다를 수 있어 CSP마다 개별 확인이 필요합니다.

## 10. 이 설계에서 다루지 않는 것

| 항목 | 이유 |
|---|---|
| 다중 OS(Rocky 등) 지원 | `KubeadmUserData`는 Pulumi 타입과 무관해 이 전환과 독립이다 |
| Cluster API 이전 | 프로비저닝 도메인 전체 재작성 규모 |
| Gradle feature variants | YAML 전환이 성공하면 CSP별 아티팩트 분리가 불필요해진다 |
| `pulumi cancel` | DIY 백엔드에서 지원되지 않는다. 락 해제는 상태 버킷의 `.pulumi/locks/` 조작이 유일한 수단이며 런북 과제다 |
