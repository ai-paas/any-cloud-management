# VM Options API

사용자에게 보여주는 기본 용어는 `VM Options`입니다.

이 API는 VM 기반 Kubernetes 클러스터를 만들기 전에 필요한 선택 항목을 제공합니다.

- CSP Provider
- Region
- VM Spec
- OS Image
- 추천 기본값
  - 추천 Region
  - 추천 VM Spec
  - 추천 OS Image
  - 기본 Worker 수
  - 기본 Kubernetes 버전

## 엔드포인트

VM Options 카탈로그 (`/v1/providers`):

- `GET /v1/providers`
- `GET /v1/providers/{provider}/regions`
- `GET /v1/providers/{provider}/specs?region=...&keyword=...&gpuOnly=...&limit=...`
- `GET /v1/providers/{provider}/images?region=...&keyword=...&architecture=...&owner=...&limit=...`

VM Cluster 조회 (통합 `/v1/clusters`):

- `GET /v1/clusters?provider=...&environment=...&status=...`
- `GET /v1/clusters/{clusterName}`

> 전체 v1 API surface 는 [v1-reference.md](./v1-reference.md) 를 참고합니다.

## 성공 응답 형식

VM Options API의 성공 응답은 공통 envelope를 사용합니다.

- `success`
- `status`
- `message`
- `data`

## 역할 분리

- `VM Options`
  - 사용자가 VM 기반 클러스터 생성을 위해 선택하는 참조 데이터
- `VM Cluster`
  - 실제 VM과 Kubernetes 클러스터의 생성/목록/상태/삭제를 담당합니다.
- `Registered Cluster`
  - 이미 존재하는 외부 클러스터 등록 및 운영을 담당합니다.

## Provider 별 실시간 조회 범위

- `AWS`
  - 리전, VM 스펙, OS 이미지를 조회합니다.
- `GCP`
  - 리전, VM 스펙, OS 이미지를 조회합니다.
- `Azure`
  - 리전, VM 스펙(Resource SKU), 대표 OS 이미지를 조회합니다.
- `OpenStack`
  - 리전, flavor, OS 이미지를 조회합니다.
- `DigitalOcean`
  - 리전, Droplet size, public OS 이미지를 조회합니다.
- `Alibaba`
  - 리전, ECS instance type, 공개 OS 이미지를 조회합니다.
- `OCI`
  - region subscription, shape, platform image 를 조회합니다.
- `Proxmox`
  - node, template VM, node capacity 기반 spec/image 를 조회합니다.
  - spec은 flavor catalog가 아니라 node capacity를 바탕으로 생성됩니다.

## 응답 메모

- 사용자-facing 용어는 `VM Options`
- 내부 구현과 외부 노출 모두 `VM Options` 기준으로 정리합니다.
- Provider 응답에는 `recommendedRegion`, `recommendedVmSpec`, `recommendedOsImage`, `defaultWorkerCount`, `defaultKubernetesVersion`가 포함됩니다.
- VM Spec / OS Image 응답에는 `recommended`, `recommendationReason`가 포함됩니다.
- VM Cluster 목록/상세 응답에는 `status`와 함께 사람이 읽기 쉬운 `statusDetail`이 포함됩니다.
- VM Cluster 목록은 `provider`, `environment`, `status` 쿼리 파라미터로 필터링할 수 있습니다.
