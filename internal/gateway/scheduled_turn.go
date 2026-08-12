package gateway

import (
	"encoding/json"
	"errors"
	"fmt"
	"sync"
)

var errScheduledTurnNeedsInteraction = errors.New("scheduled task requires interactive input")

type observedTurn struct {
	sourceID string
	done     chan error
	once     sync.Once
	mu       sync.Mutex
	failure  error
}

func newObservedTurn(sourceID string) *observedTurn {
	return &observedTurn{sourceID: sourceID, done: make(chan error, 1)}
}

func (turn *observedTurn) rememberFailure(err error) {
	if err == nil {
		return
	}
	turn.mu.Lock()
	if turn.failure == nil {
		turn.failure = err
	}
	turn.mu.Unlock()
}

func (turn *observedTurn) result() error {
	turn.mu.Lock()
	defer turn.mu.Unlock()
	return turn.failure
}

func (turn *observedTurn) finish(err error) {
	turn.rememberFailure(err)
	turn.once.Do(func() { turn.done <- turn.result() })
}

func (session *piSession) beginObservedTurn(sourceID string) (<-chan error, error) {
	session.observedTurnMu.Lock()
	defer session.observedTurnMu.Unlock()
	if session.observedTurn != nil {
		return nil, errors.New("a scheduled turn is already being observed")
	}
	turn := newObservedTurn(sourceID)
	session.observedTurn = turn
	return turn.done, nil
}

func (session *piSession) finishObservedTurn(err error) {
	session.observedTurnMu.Lock()
	turn := session.observedTurn
	session.observedTurn = nil
	session.observedTurnMu.Unlock()
	if turn != nil {
		turn.finish(err)
	}
}

func (session *piSession) observeTurnOutput(message []byte, envelope piOutputEnvelope) {
	session.observedTurnMu.Lock()
	turn := session.observedTurn
	session.observedTurnMu.Unlock()
	if turn == nil {
		return
	}

	switch envelope.Type {
	case "response":
		if envelope.ID == turn.sourceID && envelope.Command == "prompt" && !envelope.Success {
			message := envelope.Error
			if message == "" {
				message = "pi rejected the scheduled prompt"
			}
			session.finishObservedTurn(errors.New(message))
		}

	case "message_end":
		var event struct {
			Message struct {
				Role         string `json:"role"`
				StopReason   string `json:"stopReason"`
				ErrorMessage string `json:"errorMessage"`
			} `json:"message"`
			ErrorMessage string `json:"errorMessage"`
		}
		if json.Unmarshal(message, &event) == nil && event.Message.Role == "assistant" {
			switch event.Message.StopReason {
			case "error":
				detail := event.Message.ErrorMessage
				if detail == "" {
					detail = event.ErrorMessage
				}
				if detail == "" {
					detail = "model execution failed"
				}
				turn.rememberFailure(errors.New(detail))
			case "aborted":
				turn.rememberFailure(errors.New("model execution was aborted"))
			}
		}

	case "extension_ui_request":
		if interactiveUIRequestMethod(envelope.Method) {
			if err := session.resolveUIRequest(
				session.done,
				envelope.ID,
				scheduledUIRejectCommand(envelope.ID),
			); err != nil {
				session.logger.Warn(
					"cancel scheduled task UI request",
					"request_id", envelope.ID,
					"error", err,
				)
			}
			session.finishObservedTurn(errScheduledTurnNeedsInteraction)
		}

	case "agent_settled":
		session.finishObservedTurn(turn.result())
	}
}

func scheduledUIRejectCommand(requestID string) []byte {
	command, _ := json.Marshal(map[string]any{
		"type":      "extension_ui_response",
		"id":        requestID,
		"cancelled": true,
	})
	return command
}

func scheduledPromptCommand(sourceID, prompt string) ([]byte, error) {
	command, err := json.Marshal(map[string]any{
		"id":      sourceID,
		"type":    "prompt",
		"message": prompt,
	})
	if err != nil {
		return nil, fmt.Errorf("encode scheduled prompt: %w", err)
	}
	return command, nil
}
