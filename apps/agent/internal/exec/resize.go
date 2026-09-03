// resizeQueue — remotecommand.TerminalSizeQueue 구현.
//
// client-go 의 SPDY executor 는 Next() *TerminalSize 를 polling 하며,
// nil 반환 시 resize 종료로 간주. 본 queue 는 channel-backed 라
// push 가 호출될 때까지 Next 가 블록.
package exec

import (
	"context"
	"sync"

	"k8s.io/client-go/tools/remotecommand"
)

type resizeQueue struct {
	ctx    context.Context
	ch     chan remotecommand.TerminalSize
	closed bool
	mu     sync.Mutex
}

func newResizeQueue(ctx context.Context) *resizeQueue {
	return &resizeQueue{
		ctx: ctx,
		// Buffer 8 — burst (drag resize) 흡수. 넘치면 drop (consoles tolerant).
		ch: make(chan remotecommand.TerminalSize, 8),
	}
}

// Next — client-go interface. ctx 종료 또는 ch close 시 nil.
func (q *resizeQueue) Next() *remotecommand.TerminalSize {
	select {
	case <-q.ctx.Done():
		return nil
	case s, ok := <-q.ch:
		if !ok {
			return nil
		}
		return &s
	}
}

// push — non-blocking. Buffer full 이면 drop.
func (q *resizeQueue) push(s remotecommand.TerminalSize) {
	q.mu.Lock()
	defer q.mu.Unlock()
	if q.closed {
		return
	}
	select {
	case q.ch <- s:
	default:
		// drop — burst absorber 가 가득찰 정도면 사용자 시점에선 최신 1-2 개만 의미 있음.
	}
}
