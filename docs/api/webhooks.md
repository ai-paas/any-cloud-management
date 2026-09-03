# Webhook 통합 가이드 (외부 포털 / 시스템용)

`anycloud` 백엔드는 클러스터 상태가 바뀔 때마다 **HTTP POST** 로 외부 시스템에 이벤트를 푸시합니다.
별도 포털이 polling 없이 event-driven 으로 동기화하기 위한 채널입니다.

본 문서는 **수신측** 이 구현해야 할 사항을 정리합니다.

---

## 1. 활성화 & 환경변수

```yaml
webhook:
  enabled: ${WEBHOOK_ENABLED:false}
  urls: ${WEBHOOK_URLS:}            # 콤마 구분. 예: https://portal/anycloud/events
  events: ${WEBHOOK_EVENTS:}        # 비어 있으면 전체. 예: vm-cluster.ready,vm-cluster.failed
  signing-secret: ${WEBHOOK_SECRET:}
  timeout-ms: ${WEBHOOK_TIMEOUT_MS:5000}
  max-attempts: ${WEBHOOK_MAX_ATTEMPTS:3}
  initial-interval-ms: ${WEBHOOK_INITIAL_INTERVAL_MS:500}
```

- `urls` 가 비어 있으면 백엔드는 아무것도 보내지 않습니다.
- `signing-secret` 가 비어 있으면 서명 헤더가 부착되지 않습니다 (수신측은 None 처리해야 합니다).

---

## 2. 이벤트 타입

| Event type | 발생 조건 | 비즈니스 액션 |
|---|---|---|
| `vm-cluster.ready` | PROVISION → BOOTSTRAP → VERIFY 통과, READY 전환 | 포털: 클러스터 등록 / "사용 가능" 표시 |
| `vm-cluster.failed` | step 실패, retry 한도 미도달 (FAILED) | 포털: "재시도 중" 또는 사용자 통지 |
| `vm-cluster.blocked` | retry 한도 도달 → BLOCKED (수동 개입 필요) | 포털: 운영자 ticket 자동 생성 권장 |
| `vm-cluster.deleted` | DESTROY 완료, DELETED 전환 | 포털: 클러스터 archive / soft-delete |

이벤트 타입은 외부 계약입니다. 백엔드는 기존 타입을 절대 제거하지 않으며, 새 타입은 추가만 합니다.
수신측은 모르는 타입을 무시 (`200 OK` 응답 권장) 하는 forward-compatible 디자인을 권장합니다.

---

## 3. 페이로드 (JSON)

```json
{
  "id": "7c4f1a2e-3b9d-4a8c-9e7f-2b1d3e5a8c0f",
  "type": "vm-cluster.ready",
  "timestamp": "2026-05-11T03:45:21.123Z",
  "data": {
    "clusterName": "demo-aws-01",
    "provisioningId": "u-9f3e1c...",
    "provider": "AWS",
    "region": "ap-northeast-2",
    "environment": "dev",
    "status": "READY",
    "stackName": "anycloud-demo-aws-01"
  },
  "links": {
    "resource": "/v1/clusters/demo-aws-01",
    "events": "/v1/clusters/demo-aws-01/events",
    "operations": "/v1/clusters/demo-aws-01/operations"
  }
}
```

`failed` / `blocked` 이벤트는 `data.error` 필드가 추가로 포함됩니다 (예외 메시지).

`data` 객체의 키 집합은 미래에 늘어날 수 있으므로 수신측은 **알 수 없는 키는 무시**해야 합니다.

`links` 는 v1 API 응답 envelope 와 동일한 HATEOAS-lite 패턴입니다. 수신측이 후속 API 호출 URL 을
자체 조립할 필요 없이 그대로 사용합니다. links 가 없는 케이스도 있으므로 nullable 처리를 권장합니다.

---

## 4. HTTP 헤더

| Header | 예시 값 | 설명 |
|---|---|---|
| `Content-Type` | `application/json` | 고정 |
| `X-Anycloud-Event` | `vm-cluster.ready` | event type — 라우팅용 |
| `X-Anycloud-Event-Id` | `7c4f1a2e-…` | 멱등키. 같은 id 가 두 번 오면 같은 이벤트 |
| `X-Anycloud-Timestamp` | `2026-05-11T03:45:21.123Z` | 발신 시각 |
| `X-Anycloud-Signature` | `sha256=a3b9…` | HMAC-SHA256(body) hex. signing-secret 설정 시에만 |

`X-Request-Id` 와는 별개 채널입니다. 수신측이 응답에 자체 `X-Request-Id` 를 넣어도 무방합니다.

---

## 5. 시그니처 검증

본 백엔드는 HMAC-SHA256 으로 본문 전체를 서명합니다. 수신측은 다음을 수행해야 합니다.

1. 원시 body 를 그대로 (재직렬화 없이) 보관합니다.
2. 동일 secret 으로 HMAC-SHA256 을 계산합니다.
3. 헤더의 `sha256=` 접두사 제거 후 **constant-time** 으로 비교합니다 (timing-safe).
4. 일치하지 않으면 401 을 반환합니다.

### 5.1 Node.js (Express) 예시

```js
const crypto = require('crypto');
const express = require('express');
const app = express();
const SECRET = process.env.ANYCLOUD_WEBHOOK_SECRET;

// raw body 보관 — JSON.parse 이전에 buffer 보존
app.use('/anycloud/events', express.raw({ type: 'application/json' }));

app.post('/anycloud/events', (req, res) => {
  const sig = (req.header('X-Anycloud-Signature') || '').replace(/^sha256=/, '');
  const expected = crypto.createHmac('sha256', SECRET).update(req.body).digest('hex');
  const ok = sig.length === expected.length
    && crypto.timingSafeEqual(Buffer.from(sig, 'hex'), Buffer.from(expected, 'hex'));
  if (!ok) return res.status(401).send('bad signature');

  const event = JSON.parse(req.body.toString('utf8'));
  console.log('received', event.type, event.data.clusterName);
  // TODO: dedupe by event.id, persist, emit to UI bus
  res.sendStatus(200);
});

app.listen(8080);
```

### 5.2 Python (Flask) 예시

```python
import hashlib, hmac, os
from flask import Flask, request, abort
app = Flask(__name__)
SECRET = os.environ['ANYCLOUD_WEBHOOK_SECRET'].encode()

@app.post('/anycloud/events')
def events():
    raw = request.get_data()  # 원시 body
    sig_hdr = request.headers.get('X-Anycloud-Signature', '').removeprefix('sha256=')
    expected = hmac.new(SECRET, raw, hashlib.sha256).hexdigest()
    if not hmac.compare_digest(sig_hdr, expected):
        abort(401)
    event = request.get_json()
    # TODO: dedupe by event['id'], persist
    return '', 200
```

### 5.3 Java (Spring) 예시

```java
@PostMapping("/anycloud/events")
public ResponseEntity<Void> receive(@RequestBody byte[] rawBody,
                                    @RequestHeader("X-Anycloud-Signature") String sig) throws Exception {
    String expected = hmacHex(secret, rawBody);
    if (!MessageDigest.isEqual(
            sig.replaceFirst("^sha256=", "").getBytes(StandardCharsets.UTF_8),
            expected.getBytes(StandardCharsets.UTF_8))) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }
    var event = objectMapper.readValue(rawBody, WebhookEvent.class);
    // dedupe / persist / emit
    return ResponseEntity.ok().build();
}

private static String hmacHex(byte[] key, byte[] msg) throws Exception {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(key, "HmacSHA256"));
    byte[] d = mac.doFinal(msg);
    StringBuilder sb = new StringBuilder(d.length * 2);
    for (byte b : d) sb.append(String.format("%02x", b));
    return sb.toString();
}
```

---

## 6. 재시도 & 멱등성

- 백엔드는 비-2xx 응답을 실패로 간주하고 **지수 backoff** 로 재시도합니다 (기본 max 3회, 초기 500ms).
- 같은 이벤트가 여러 번 도착할 수 있습니다 (at-least-once).
- 수신측은 `X-Anycloud-Event-Id` 를 멱등키로 사용하여 중복 처리를 차단해야 합니다.
  예를 들어 받은 ID 를 30일 정도의 짧은 TTL 캐시 (Redis/DB) 에 저장하고, 존재하면 즉시 `200` 으로 응답한 뒤 비즈니스 로직을 skip 합니다.

권장 응답 코드는 다음과 같습니다.

| 상황 | 응답 |
|---|---|
| 정상 수신 / 비즈니스 처리 완료 | `200` 또는 `204` |
| 시그니처 불일치 | `401` (재시도 무의미 — 백엔드 측 secret 확인 필요) |
| 알 수 없는 event type / 잘못된 payload | `200` (재시도해도 결과 동일) |
| 수신측 일시 장애 (DB down 등) | `5xx` (백엔드가 재시도) |
| 정상이지만 시간이 걸림 | `200` 빠르게 ack 후 비동기 처리 권장 (5초 timeout) |

---

## 7. 메트릭 / 디버깅

백엔드 측 Prometheus 메트릭은 다음과 같습니다.

```
anycloud_webhook_delivery_total{event="vm-cluster.ready", url="https://portal/...", result="success"}
anycloud_webhook_delivery_total{..., result="http_5xx"}
anycloud_webhook_delivery_total{..., result="error"}     # 네트워크/timeout
anycloud_webhook_delivery_total{..., result="giveup"}    # 재시도 한도 도달
```

수신측 알람으로는 다음을 권장합니다.

- 401 발생 → 양쪽 secret mismatch 의심.
- `giveup` 누적 → 수신측 endpoint URL / 가용성을 확인합니다.

---

## 8. 보안 권장사항

- HTTPS 가 필수입니다. HTTP URL 은 디버깅 한정으로 사용합니다.
- `signing-secret` 는 32+ 바이트 random 값으로 설정하고 정기적으로 교체합니다.
- `X-Anycloud-Timestamp` 가 너무 오래된 (예: 5분 이상) 이벤트는 replay 가능성이 있으므로 거부를 권장합니다.
- 수신측은 IP 화이트리스트 (gateway / VPC peering) 와 시그니처를 **둘 다** 적용합니다.
