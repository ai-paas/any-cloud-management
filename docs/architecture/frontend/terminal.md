# Frontend 통합 — Pod Exec / Node Debug Shell

xterm.js 기반 인터랙티브 터미널 통합. 공통 가정은
[`../frontend-integration.md`](../frontend-integration.md) 참고.

## 1. Pod Exec 터미널 (xterm.js)

WebSocket 프로토콜 (cluster-agent starter `PodExec`) 은 다음과 같습니다.

- **Client → Server**:
  - Binary frame = stdin bytes
  - Text frame `{"type":"resize","cols":N,"rows":N}` = PTY resize
  - Text frame `{"type":"stdin","data":"<base64>"}` = base64 stdin (binary 미지원 client)
- **Server → Client**:
  - Binary frame = stdout/stderr (TTY 모드에선 머지됨)
  - 마지막 text frame `{"type":"end","exitCode":N,"errorCode":"...","message":"..."}`

```ts
import { Terminal } from "xterm";
import { FitAddon } from "xterm-addon-fit";

function openPodExec(opts: {
  cluster: string;
  namespace: string;
  pod: string;
  container?: string;
  command?: string[];
  el: HTMLDivElement;
}) {
  const term = new Terminal({ convertEol: false, cursorBlink: true });
  const fit = new FitAddon();
  term.loadAddon(fit);
  term.open(opts.el);
  fit.fit();

  const qs = new URLSearchParams({
    tty: "true",
    cols: String(term.cols),
    rows: String(term.rows),
  });
  if (opts.container) qs.set("container", opts.container);
  if (opts.command?.length) qs.set("command", opts.command.join(","));

  const proto = location.protocol === "https:" ? "wss:" : "ws:";
  const ws = new WebSocket(
    `${proto}//${location.host}/v1/clusters/${opts.cluster}` +
      `/pods/${opts.namespace}/${opts.pod}/exec?${qs}`,
  );
  ws.binaryType = "arraybuffer";

  // Server → Terminal
  ws.onmessage = (ev) => {
    if (ev.data instanceof ArrayBuffer) {
      term.write(new Uint8Array(ev.data));
      return;
    }
    // text frame — JSON
    try {
      const msg = JSON.parse(ev.data);
      if (msg.type === "end") {
        term.writeln(`\r\n\x1b[33m[exited ${msg.exitCode}] ${msg.message ?? ""}\x1b[0m`);
      }
    } catch {
      term.write(ev.data);
    }
  };
  ws.onclose = () => term.writeln("\r\n\x1b[31m[connection closed]\x1b[0m");

  // Terminal → Server: stdin
  const enc = new TextEncoder();
  term.onData((d) => {
    if (ws.readyState === WebSocket.OPEN) ws.send(enc.encode(d));
  });

  // Resize → server
  const ro = new ResizeObserver(() => {
    fit.fit();
    if (ws.readyState === WebSocket.OPEN) {
      ws.send(JSON.stringify({ type: "resize", cols: term.cols, rows: term.rows }));
    }
  });
  ro.observe(opts.el);

  return () => {
    ro.disconnect();
    ws.close();
    term.dispose();
  };
}
```

**참고**:
- `tty=true` 가 default 입니다 — 인터랙티브 shell 용입니다. `false` 이면 stdout/stderr 가 분리됩니다.
- `command` 는 comma-separated (`/bin/bash,-l`) 입니다. default 는 `/bin/sh`
- WebSocket buffer 는 backend 가 64KB 입니다 — 더 큰 출력은 chunk 됩니다.

---

## 2. Node Debug Shell

2-step 으로 구성됩니다.

```ts
async function openNodeShell(cluster: string, node: string, el: HTMLDivElement) {
  // 1) Debug pod 생성 (host root shell — privileged)
  const res = await fetch(
    `/v1/clusters/${cluster}/nodes/${node}/debug-pod`,
    { method: "POST" },
  );
  if (!res.ok) {
    throw new Error(await res.text());
  }
  const { data } = await res.json();
  // data: { namespace, podName, expiresAt, nodeName }

  // 2) 기존 PodExec WebSocket 재사용
  return openPodExec({
    cluster,
    namespace: data.namespace,
    pod: data.podName,
    command: ["bash"],     // nsenter 가 이미 entrypoint
    el,
  });
}
```

운영자에게 노출 시 **경고가 필수입니다** — host root + 모든 노드 namespace 접근이 가능합니다. RBAC + UI confirm modal 을 권장합니다.
