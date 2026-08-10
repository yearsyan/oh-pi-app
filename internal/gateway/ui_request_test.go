package gateway

import (
	"errors"
	"io"
	"log/slog"
	"testing"
	"time"
)

func TestUIRequestResponseIsForwardedAtMostOnce(t *testing.T) {
	session := newPiSession(piSessionConfig{
		ID:             "11111111-1111-4111-8111-111111111111",
		InputQueueSize: 2,
		SessionIdle:    time.Hour,
		NewSession:     true,
		Logger:         slog.New(slog.NewTextHandler(io.Discard, nil)),
	})
	request := []byte(`{"type":"extension_ui_request","id":"dialog-1","method":"confirm"}`)
	session.handleOutput(request)
	session.handleOutput(request)

	session.replayMu.Lock()
	if len(session.pendingUI) != 1 || len(session.pendingUIOrder) != 1 {
		t.Fatalf("deduplicated pending UI state = %#v / %#v", session.pendingUI, session.pendingUIOrder)
	}
	session.replayMu.Unlock()

	clientDone := make(chan struct{})
	first := []byte(`{"type":"extension_ui_response","id":"dialog-1","confirmed":true}`)
	if err := session.resolveUIRequest(clientDone, "dialog-1", first); err != nil {
		t.Fatalf("resolve first response: %v", err)
	}
	if got := <-session.input; string(got) != string(first) {
		t.Fatalf("forwarded response = %s, want %s", got, first)
	}

	second := []byte(`{"type":"extension_ui_response","id":"dialog-1","cancelled":true}`)
	if err := session.resolveUIRequest(clientDone, "dialog-1", second); !errors.Is(err, errUIRequestAlreadyResolved) {
		t.Fatalf("second response error = %v, want already resolved", err)
	}
	select {
	case duplicate := <-session.input:
		t.Fatalf("duplicate response was forwarded: %s", duplicate)
	default:
	}
}

func TestExtensionUIResponseRequiresValidID(t *testing.T) {
	for _, command := range [][]byte{
		[]byte(`{"type":"extension_ui_response"}`),
		[]byte(`{"type":"extension_ui_response","id":""}`),
		[]byte(`{"type":"extension_ui_response","id":42}`),
	} {
		if _, err := extensionUIResponseID(command); err == nil {
			t.Fatalf("invalid response %s was accepted", command)
		}
	}
}
