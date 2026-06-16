# Frontend ↔ Backend OpenAPI Integration

anycloud backend 의 OpenAPI 3.x spec 을 frontend 의 type-safe client 자동 생성에 활용. API
변경 시 frontend 가 **컴파일 에러로 즉시 인지** — 수동 type 동기화 불요.

## 1. Backend 측 publish (CI 자동화)

`.github/workflows/publish-openapi.yml` 가 매 main push / version tag push 마다:

1. backend 부팅
2. `GET /v3/api-docs` → `openapi.json`
3. `GET /v3/api-docs.yaml` → `openapi.yaml`
4. artifact upload (90일 보존) + gh-pages 의 `openapi/` 경로로 publish

publish 결과:
```
https://<owner>.github.io/<repo>/openapi/openapi.json
https://<owner>.github.io/<repo>/openapi/openapi.yaml
```

versioned (tag push) 일 때는 `openapi-v0.1.0.json` 등 추가 (workflow 의 추가 step 으로 확장).

## 2. Frontend 측 client 생성

frontend repo 의 `package.json`:

```json
{
  "scripts": {
    "openapi:fetch": "curl -fsS https://<owner>.github.io/<repo>/openapi/openapi.json -o openapi.json",
    "openapi:gen": "npx @openapitools/openapi-generator-cli generate -i openapi.json -g typescript-axios -o src/api/generated --additional-properties=supportsES6=true,withInterfaces=true",
    "openapi:sync": "npm run openapi:fetch && npm run openapi:gen"
  }
}
```

### 사용 예시 (frontend 의 React / Vue / Svelte 무관)

```typescript
import { ClusterApi, Configuration } from './api/generated';

const api = new ClusterApi(new Configuration({
    basePath: 'https://anycloud-api.npaas.kr',
    accessToken: () => getAuthToken(),
}));

// 컴파일 타임 type-checked
const result = await api.listClusters();
// CreateClusterRequest 필드가 backend 와 100% 동기화
await api.createCluster({ name: 'demo', source: { type: 'vm' /* ... */ } });
```

### API 변경 회귀 흐름

```
[backend PR] CreateClusterRequest 에 새 field 'gpu' 추가
    ↓ main merge
[CI] publish-openapi.yml 실행 — gh-pages 갱신
    ↓
[frontend dev] npm run openapi:sync
    ↓
[frontend code] 새 field 사용 가능. 기존 코드 영향 0 (additive).

[backend PR] CreateClusterRequest 의 'name' → 'clusterName' rename
    ↓ main merge
[CI] publish-openapi.yml
    ↓
[frontend] npm run openapi:sync
    ↓
[frontend code] TS 컴파일 에러 — `clusterName` 가 새 field name. 즉시 수정 필요.
```

## 3. Versioning + breaking change 방지

매 PR 의 CI 에 OpenAPI **diff check** 추가 권장 (향후 확장):

```yaml
- name: OpenAPI breaking change check
  run: |
    curl -fsS https://<owner>.github.io/<repo>/openapi/openapi.json -o baseline.json
    npx @apidevtools/swagger-cli validate dist/openapi/openapi.json
    npx openapi-diff baseline.json dist/openapi/openapi.json --fail-on-breaking
```

→ breaking change (field 삭제 / required 변경 / path 삭제) PR 시 즉시 fail.

## 4. 도구 선택

| 도구 | 언어 | 비고 |
|---|---|---|
| `openapi-generator-cli` (OpenAPITools) | 모든 언어 | 가장 범용. Java/TS/Python/Go 등 50+ generator |
| `orval` | TS 만 | React Query / SWR 통합 강함 |
| `kubb` | TS 만 | type 만 생성 (light) — runtime client 직접 작성 |

추천: **openapi-generator-cli + typescript-axios** — 검증됨 + Axios 가 frontend 친화.

## 5. 향후 확장

- **Mock server** — frontend dev 가 backend 안 띄우고도 mock 응답 받기 (`prism mock openapi.json`)
- **API gateway 통합** — Kong / Apigee 같은 gateway 가 OpenAPI 로 route 생성
- **Postman / Insomnia import** — runtime test
- **AsyncAPI** — gRPC 의 reverse-tunnel + SSE 같은 streaming endpoint 도 spec 표준화
