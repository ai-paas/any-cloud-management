package exec

import (
	"testing"

	agentv1 "anycloud/agent/internal/gen/agent/v1"
)

// remotecommand 는 exec 시작 시 resize 큐에서 첫 크기를 읽는다. 비어 있으면 PTY 가 0x0 으로
// 열리고 tcell TUI(k9s)는 화면을 지운 뒤 아무것도 그리지 않는다. 사용자가 나중에 보내는
// resize 로는 복구되지 않는다.

func TestRequestedSizeIsUsed(t *testing.T) {
	got := initialTerminalSize(&agentv1.TerminalSize{Cols: 120, Rows: 30})

	if got.Width != 120 || got.Height != 30 {
		t.Fatalf("크기 = %dx%d, want 120x30", got.Width, got.Height)
	}
}

func TestMissingSizeFallsBackToEightyByTwentyFour(t *testing.T) {
	// 크기를 싣지 않던 예전 백엔드와도 붙어야 한다. 0x0 으로 열면 화면이 비어 버린다.
	for _, size := range []*agentv1.TerminalSize{nil, {}, {Cols: 100}, {Rows: 40}} {
		got := initialTerminalSize(size)
		if got.Width == 0 || got.Height == 0 {
			t.Fatalf("%v → %dx%d, 0 이 남았다", size, got.Width, got.Height)
		}
	}
}

func TestZeroDimensionsAreReplacedIndividually(t *testing.T) {
	got := initialTerminalSize(&agentv1.TerminalSize{Cols: 200, Rows: 0})

	if got.Width != 200 || got.Height != 24 {
		t.Fatalf("크기 = %dx%d, want 200x24", got.Width, got.Height)
	}
}
