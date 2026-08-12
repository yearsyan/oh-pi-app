package gateway

import (
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"testing"
	"time"
)

func TestScheduledTurnCancelsInteractiveRequestAndFails(t *testing.T) {
	session := newPiSession(piSessionConfig{
		ID:             "scheduled-session",
		InputQueueSize: 4,
		Logger:         slog.New(slog.NewTextHandler(io.Discard, nil)),
	})
	session.settled = false
	requestID := "interactive-1"
	session.pendingUI[requestID] = pendingUIRequest{id: requestID}
	session.pendingUIOrder = []string{requestID}
	done, err := session.beginObservedTurn("scheduled-prompt")
	if err != nil {
		t.Fatal(err)
	}

	message := []byte(`{"type":"extension_ui_request","id":"interactive-1","method":"confirm"}`)
	session.observeTurnOutput(message, piOutputEnvelope{
		Type: "extension_ui_request", ID: requestID, Method: "confirm",
	})

	select {
	case runErr := <-done:
		if !errors.Is(runErr, errScheduledTurnNeedsInteraction) {
			t.Fatalf("turn error = %v", runErr)
		}
	case <-time.After(time.Second):
		t.Fatal("observed turn did not finish")
	}

	select {
	case command := <-session.input:
		var response struct {
			Type      string `json:"type"`
			ID        string `json:"id"`
			Cancelled bool   `json:"cancelled"`
		}
		if err := json.Unmarshal(command, &response); err != nil {
			t.Fatal(err)
		}
		if response.Type != "extension_ui_response" || response.ID != requestID || !response.Cancelled {
			t.Fatalf("response = %#v", response)
		}
	case <-time.After(time.Second):
		t.Fatal("interactive request was not cancelled")
	}
	if _, pending := session.pendingUI[requestID]; pending {
		t.Fatal("interactive request remained pending")
	}
}

func TestScheduledTurnRecordsTopLevelModelError(t *testing.T) {
	session := newPiSession(piSessionConfig{
		ID:             "scheduled-session",
		InputQueueSize: 1,
		Logger:         slog.New(slog.NewTextHandler(io.Discard, nil)),
	})
	done, err := session.beginObservedTurn("scheduled-prompt")
	if err != nil {
		t.Fatal(err)
	}
	message := []byte(`{"type":"message_end","message":{"role":"assistant","stopReason":"error"},"errorMessage":"provider failed"}`)
	session.observeTurnOutput(message, piOutputEnvelope{Type: "message_end"})
	session.observeTurnOutput([]byte(`{"type":"agent_settled"}`), piOutputEnvelope{Type: "agent_settled"})

	select {
	case runErr := <-done:
		if runErr == nil || runErr.Error() != "provider failed" {
			t.Fatalf("turn error = %v", runErr)
		}
	case <-time.After(time.Second):
		t.Fatal("observed turn did not finish")
	}
}
