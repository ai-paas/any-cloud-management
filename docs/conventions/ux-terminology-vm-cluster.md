# UX Terminology: VM Cluster

현재 시스템의 구현은 `VM Options`, `VM Cluster`, `Pulumi` 중심으로 정리하고, 사용자 UX도 `CSP VM 기반 Kubernetes 생성/관리`로 맞추는 편이 더 직관적입니다.

## 추천 용어

- `VM Options`
- `VM 스펙`
- `OS 이미지`
- `VM 기반 클러스터 생성`
- `클러스터 생성 상태`
- `클러스터 연결 재등록`

## 권장 사용자 흐름

1. CSP 선택
2. 리전 선택
3. VM 스펙 선택
4. OS 이미지 선택
5. VM 기반 Kubernetes 클러스터 생성
6. 생성 상태 확인
7. 생성 완료 후 기존 cluster 관리 기능으로 운영

## 백엔드 전략

- 내부 구현 용어는 유지합니다.
  - `VmOptionsQueryService`, `PulumiProvisioningService`
- 외부 노출 API 는 v1 RESTful 통합 자원을 사용합니다.
  - `/v1/providers/...` — VM Options 카탈로그입니다.
  - `/v1/clusters` — K8s cluster 등록 자원 (manual register + agent self-registered).
  - `/v1/vms` — CSP VM 그룹 자원 (Pulumi-managed). body 의 `vmGroupName` 으로 식별. 그룹 안에 master + worker 인스턴스 N개 포함.

## 도메인 구분

- `VM Options`
  - CSP, 리전, VM 스펙, OS 이미지를 선택합니다.
- `VM Cluster`
  - VM 기반 Kubernetes 의 생성/상태/삭제를 담당합니다.
- `Registered Cluster`
  - 이미 존재하는 kubeconfig 기반 클러스터의 등록/운영을 담당합니다.

이 구조는 게이트웨이/프론트에 직관적인 도메인 언어를 제공합니다.
