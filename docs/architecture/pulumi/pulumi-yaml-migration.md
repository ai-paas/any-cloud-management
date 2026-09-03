# Pulumi 타입 SDK를 생성 YAML로 교체

`cluster-provisioning-spring-boot-starter`가 의존하는 Pulumi Java provider SDK 7종(732MB)을 제거하고,
Java가 생성한 YAML 프로그램을 Pulumi CLI로 실행하는 방식으로 바꾸는 설계입니다.

관련 문서는 [pulumi-multicloud-k8s-blueprint.md](pulumi-multicloud-k8s-blueprint.md),
[pulumi-runtime-with-gateway.md](pulumi-runtime-with-gateway.md),
[pulumi-gpu-support.md](pulumi-gpu-support.md),
[../vmcluster-workflow.md](../vmcluster-workflow.md) 입니다.

## 1. 왜 바꾸는가

이 starter는 **다른 프로젝트가 가져다 쓰는 공개 아티팩트**입니다. 릴리스 태그마다 GHCR Maven으로
publish되고 Apache 2.0 POM을 갖습니다. 그런데 CSP SDK 7종이 `api` 스코프로 선언되어 있어 **가져다 쓰는
모든 프로젝트가 732MB를 그대로 물려받습니다.**

| SDK | 크기 | SDK | 크기 |
|---|---|---|---|
| azure-native | 220MB | alicloud | 66MB |
| oci | 186MB | digitalocean | 3.7MB |
| aws | 137MB | openstack | 1.6MB |
| gcp | 114MB | **합계** | **732MB** |

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

### `nodes` 출력의 특수 사정

현재 `nodes`는 배열이 아니라 **JSON 문자열**입니다. Pulumi Java SDK의 일부 역직렬화 경로가 배열 값을
만나면 깨지기 때문입니다(`AbstractKubeadmProvisioner` 주석 참조).

YAML 전환 후에는 그 제약이 사라집니다 — SDK를 거치지 않고 CLI의 `stack output --json`을 파싱합니다.
다만 **이번 전환에서 형태를 바꾸지 않습니다.** `ProvisioningResultMapper`와 anycloud의
`VmClusterNodeResolver`가 JSON 문자열을 전제하므로, 계약 변경은 별도 작업으로 둡니다.

## 5. 출력 계약 보존

이것이 이번 전환의 **성공 기준**입니다. `stackOutputs()`가 돌려주는 키와 의미가 하나도 바뀌면 안 됩니다.

| 키 | 비고 |
|---|---|
| `provider`, `clusterName`, `masterVmSpec`, `workerVmSpec`, `osImage` | 정적 값 |
| `vpcId`, `masterInstanceId`, `masterPublicIp`, `masterPrivateIp`, `masterPublicDns` | 리소스 참조 |
| `apiServerUrl` | `https://${master.publicIp}:6443` 보간 |
| `sshPrivateKeyPem`, `masterSshCommand`, `kubeconfigFetchCommand` | **secret** |
| `kubeconfigRemotePath` | 정적 |
| `nodes` | JSON 문자열 (위 참조) |
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

| 단계 | 내용 | 검증 |
|---|---|---|
| 1 | `YamlProgramBuilder` + 출력 조립 + OpenStack emitter | OpenStack 스택 실제 생성 |
| 2 | AWS emitter | AWS 스택 실제 생성 |
| 3 | GCP, Azure emitter | 각 스택 실제 생성 |
| 4 | OCI, Alibaba, DigitalOcean emitter | 각 스택 실제 생성 |
| 5 | `ProviderProvisioner` 계열 제거, `build.gradle`에서 SDK 7종 제거 | 크기 실측, 전체 회귀 |

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
| 플러그인 다운로드 | 첫 실행이 느려지고 네트워크가 필요 | 컨테이너 이미지 빌드 시 `pulumi plugin install` 프리워밍 검토 |
| CSP별 미묘한 차이 | 특정 CSP만 깨짐 | 순차 이관 — 한 번에 하나씩만 위험에 노출 |

`fn::invoke`로 바뀌는 AMI 조회(AWS)와 이미지 조회(GCP, Alibaba, OCI)가 특히 주의 대상입니다. 인자
구조가 타입 SDK와 다를 수 있어 CSP마다 개별 확인이 필요합니다.

## 10. 이 설계에서 다루지 않는 것

| 항목 | 이유 |
|---|---|
| `nodes` 출력을 JSON 문자열에서 배열로 | 소비자 계약 변경이라 별도 작업. YAML 전환의 성공 기준은 "계약 불변"이다 |
| 다중 OS(Rocky 등) 지원 | `KubeadmUserData`는 Pulumi 타입과 무관해 이 전환과 독립이다 |
| Cluster API 이전 | 프로비저닝 도메인 전체 재작성 규모 |
| Gradle feature variants | YAML 전환이 성공하면 CSP별 아티팩트 분리가 불필요해진다 |
| `pulumi cancel` | DIY 백엔드에서 지원되지 않는다. 락 해제는 상태 버킷의 `.pulumi/locks/` 조작이 유일한 수단이며 런북 과제다 |
