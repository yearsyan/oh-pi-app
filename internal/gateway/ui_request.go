package gateway

import (
	"bytes"
	"encoding/json"
	"errors"
	"strings"
)

const maxUIRequestIDBytes = 256

var (
	errUIRequestAlreadyResolved = errors.New("extension UI request was already resolved")
	errUIRequestNotPending      = errors.New("extension UI request is not pending")
)

type pendingUIRequest struct {
	id      string
	payload []byte
}

func interactiveUIRequestMethod(method string) bool {
	switch method {
	case "select", "confirm", "input", "editor":
		return true
	default:
		return false
	}
}

func validUIRequestID(id string) bool {
	return id != "" && len(id) <= maxUIRequestIDBytes && !strings.HasPrefix(id, internalRPCIDPrefix)
}

func extensionUIResponseID(command []byte) (string, error) {
	var response struct {
		ID string `json:"id"`
	}
	if err := json.Unmarshal(command, &response); err != nil || !validUIRequestID(response.ID) {
		return "", errors.New("extension_ui_response requires a non-empty string id of at most 256 bytes")
	}
	return response.ID, nil
}

// acceptUIRequestLocked records one interactive request while replayMu is
// held. Request IDs are idempotency keys for the current agent turn.
func (s *piSession) acceptUIRequestLocked(id string, payload []byte) bool {
	if _, exists := s.pendingUI[id]; exists {
		return false
	}
	if _, resolved := s.resolvedUI[id]; resolved {
		return false
	}
	s.pendingUI[id] = pendingUIRequest{id: id, payload: bytes.Clone(payload)}
	s.pendingUIOrder = append(s.pendingUIOrder, id)
	return true
}

func (s *piSession) removePendingUIRequestLocked(id string) {
	delete(s.pendingUI, id)
	for index, pendingID := range s.pendingUIOrder {
		if pendingID == id {
			s.pendingUIOrder = append(s.pendingUIOrder[:index], s.pendingUIOrder[index+1:]...)
			return
		}
	}
}

// sendPendingUIRequestsLocked sends an authoritative snapshot while replayMu
// is held, so no transition can be missed between it and client registration.
func (s *piSession) sendPendingUIRequestsLocked(client *wsClient) bool {
	for _, id := range s.pendingUIOrder {
		request, exists := s.pendingUI[id]
		if !exists {
			continue
		}
		if !client.sendBlocking(mustGatewayEvent(gatewayEvent{
			Type:      "ohpi",
			Event:     "ui_request_pending",
			RequestID: request.id,
			Payload:   request.payload,
		})) {
			return false
		}
	}
	return true
}

// resolveUIRequest forwards only the first response for a pending request.
// Holding replayMu through submission makes the claim atomic with attach
// snapshots and concurrent responses from other clients.
func (s *piSession) resolveUIRequest(clientDone <-chan struct{}, id string, command []byte) error {
	s.replayMu.Lock()
	defer s.replayMu.Unlock()

	if _, pending := s.pendingUI[id]; !pending {
		if _, resolved := s.resolvedUI[id]; resolved {
			return errUIRequestAlreadyResolved
		}
		return errUIRequestNotPending
	}
	if err := s.submit(clientDone, command); err != nil {
		return err
	}

	s.removePendingUIRequestLocked(id)
	s.resolvedUI[id] = struct{}{}
	s.outputSeq++
	sequence := s.outputSeq
	resolved := mustGatewayEvent(gatewayEvent{
		Type:      "ohpi",
		Event:     "ui_request_resolved",
		RequestID: id,
	})
	// Broadcast before releasing replayMu so later pi output cannot overtake the
	// resolution notification. Muted attach clients are skipped and observe the
	// already-resolved state through their pending snapshot instead.
	s.broadcast(resolved, nil, sequence, false)
	return nil
}
