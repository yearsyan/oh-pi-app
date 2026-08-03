package gateway

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"time"

	"github.com/gorilla/websocket"
)

const internalRPCIDPrefix = "pi2ws-internal:"

type replayRecord struct {
	Seq     uint64
	Payload []byte
}

type piOutputEnvelope struct {
	Type    string `json:"type"`
	ID      string `json:"id,omitempty"`
	Command string `json:"command,omitempty"`
	Success bool   `json:"success,omitempty"`
}

type pendingHistoryRequest struct {
	id     string
	waiter chan []byte
}

func emptyHistoryResponse() []byte {
	return []byte(`{"type":"response","command":"get_entries","success":true,"data":{"entries":[],"leafId":null}}`)
}

// ensureHistory initializes the stable history snapshot for an existing pi
// session. Only one attach performs the RPC; concurrent attaches wait for it.
func (s *piSession) ensureHistory(ctx context.Context) error {
	s.historyInitMu.Lock()
	defer s.historyInitMu.Unlock()

	s.replayMu.Lock()
	ready := s.historyReady
	s.replayMu.Unlock()
	if ready {
		return nil
	}

	response, err := s.requestHistory(ctx)
	if err != nil {
		return err
	}
	s.replayMu.Lock()
	if err := s.writeHistoryCacheLocked(response, s.historyThrough); err != nil {
		s.replayMu.Unlock()
		return err
	}
	s.historyResponse = response
	s.historyReady = true
	s.replayMu.Unlock()
	return nil
}

func (s *piSession) requestHistory(ctx context.Context) ([]byte, error) {
	request, err := s.beginHistoryRequest(ctx)
	if err != nil {
		return nil, err
	}
	return s.awaitHistoryRequest(ctx, request)
}

func (s *piSession) beginHistoryRequest(ctx context.Context) (pendingHistoryRequest, error) {
	return s.beginInternalRequest(ctx, map[string]any{"type": "get_entries"})
}

func (s *piSession) beginInternalRequest(ctx context.Context, fields map[string]any) (pendingHistoryRequest, error) {
	id := fmt.Sprintf("%s%d", internalRPCIDPrefix, s.internalSequence.Add(1))
	waiter := make(chan []byte, 1)
	s.internalMu.Lock()
	s.internalWaiters[id] = waiter
	s.internalMu.Unlock()
	cleanup := func() {
		s.internalMu.Lock()
		delete(s.internalWaiters, id)
		s.internalMu.Unlock()
	}

	fields["id"] = id
	command, err := json.Marshal(fields)
	if err != nil {
		cleanup()
		return pendingHistoryRequest{}, fmt.Errorf("encode internal RPC command: %w", err)
	}
	s.inputMu.Lock()
	defer s.inputMu.Unlock()
	select {
	case s.input <- command:
		return pendingHistoryRequest{id: id, waiter: waiter}, nil
	case <-ctx.Done():
		cleanup()
		return pendingHistoryRequest{}, ctx.Err()
	case <-s.processEnd:
		cleanup()
		return pendingHistoryRequest{}, errors.New("pi session stopped while loading history")
	case <-s.done:
		cleanup()
		return pendingHistoryRequest{}, errors.New("pi session stopped while loading history")
	}
}

func (s *piSession) awaitHistoryRequest(ctx context.Context, request pendingHistoryRequest) ([]byte, error) {
	response, err := s.awaitInternalRequest(ctx, request)
	if err != nil {
		return nil, err
	}
	return normalizeHistoryResponse(response)
}

func (s *piSession) awaitInternalRequest(ctx context.Context, request pendingHistoryRequest) ([]byte, error) {
	defer func() {
		s.internalMu.Lock()
		delete(s.internalWaiters, request.id)
		s.internalMu.Unlock()
	}()
	select {
	case response := <-request.waiter:
		return response, nil
	case <-ctx.Done():
		return nil, ctx.Err()
	case <-s.processEnd:
		return nil, errors.New("pi session stopped while loading history")
	case <-s.done:
		return nil, errors.New("pi session stopped while loading history")
	}
}

func (s *piSession) setSessionName(ctx context.Context, name string) error {
	request, err := s.beginInternalRequest(ctx, map[string]any{
		"type": "set_session_name",
		"name": name,
	})
	if err != nil {
		return err
	}
	response, err := s.awaitInternalRequest(ctx, request)
	if err != nil {
		return err
	}
	var envelope piOutputEnvelope
	if err := json.Unmarshal(response, &envelope); err != nil {
		return fmt.Errorf("decode set_session_name response: %w", err)
	}
	if envelope.Type != "response" || envelope.Command != "set_session_name" || !envelope.Success {
		return fmt.Errorf("set_session_name failed: %s", response)
	}
	return nil
}

func normalizeHistoryResponse(message []byte) ([]byte, error) {
	var envelope piOutputEnvelope
	if err := json.Unmarshal(message, &envelope); err != nil {
		return nil, fmt.Errorf("decode get_entries response: %w", err)
	}
	if envelope.Type != "response" || envelope.Command != "get_entries" || !envelope.Success {
		return nil, fmt.Errorf("get_entries failed: %s", message)
	}

	var fields map[string]json.RawMessage
	if err := json.Unmarshal(message, &fields); err != nil {
		return nil, fmt.Errorf("decode get_entries fields: %w", err)
	}
	delete(fields, "id")
	normalized, err := json.Marshal(fields)
	if err != nil {
		return nil, fmt.Errorf("encode get_entries snapshot: %w", err)
	}
	return normalized, nil
}

func (s *piSession) deliverInternal(message []byte, envelope piOutputEnvelope) bool {
	if envelope.Type != "response" || !strings.HasPrefix(envelope.ID, internalRPCIDPrefix) {
		return false
	}
	s.internalMu.Lock()
	waiter := s.internalWaiters[envelope.ID]
	s.internalMu.Unlock()
	if waiter == nil {
		return false
	}
	select {
	case waiter <- bytes.Clone(message):
	default:
	}
	return true
}

func replayableOutput(outputType string) bool {
	return outputType != "" && outputType != "response"
}

func (s *piSession) handleOutput(message []byte) {
	var envelope piOutputEnvelope
	validJSON := json.Unmarshal(message, &envelope) == nil
	if validJSON && s.deliverInternal(message, envelope) {
		return
	}
	if validJSON && envelope.Type == "response" && envelope.Command == "get_state" && envelope.Success {
		s.adoptObservedSessionName(message)
	}

	s.replayMu.Lock()
	s.outputSeq++
	outputSeq := s.outputSeq
	var replayErr error
	if validJSON && replayableOutput(envelope.Type) {
		record := replayRecord{Seq: outputSeq, Payload: bytes.Clone(message)}
		replayErr = s.appendReplayLocked(record)
		messageBytes := int64(len(message))
		if replayErr == nil && !s.replayTruncated && s.replayBytes+messageBytes > s.maxReplay {
			s.replay = nil
			s.replayBytes = 0
			s.replayTruncated = true
			s.logger.Warn("active turn replay buffer exceeded limit", "limit_bytes", s.maxReplay)
		} else if replayErr == nil && !s.replayTruncated {
			s.replay = append(s.replay, record)
			s.replayBytes += messageBytes
		}
		if replayErr == nil && (envelope.Type == "message_end" || envelope.Type == "tool_execution_end" || envelope.Type == "agent_settled") {
			replayErr = s.syncReplayLocked()
		}
	}
	s.replayMu.Unlock()
	if replayErr != nil {
		s.fail(fmt.Errorf("persist pi output before broadcast: %w", replayErr))
		return
	}

	var checkpointToken uint64
	switch envelope.Type {
	case "agent_start":
		s.markAgentStarted()
	case "agent_settled":
		checkpointToken = s.markAgentSettled()
		if s.onActivity != nil {
			if err := s.onActivity(); err != nil {
				s.logger.Warn("touch settled session", "error", err)
			}
		}
	}

	if checkpointToken != 0 {
		s.scheduleCheckpoint(outputSeq, checkpointToken)
	}
	s.broadcast(message, outputSeq)
}

func (s *piSession) adoptObservedSessionName(message []byte) {
	if s.onName == nil {
		return
	}
	var response struct {
		Data struct {
			SessionName *string `json:"sessionName"`
		} `json:"data"`
	}
	if err := json.Unmarshal(message, &response); err != nil || response.Data.SessionName == nil {
		return
	}
	name, err := normalizeSessionName(*response.Data.SessionName)
	if err != nil {
		s.logger.Warn("ignore invalid session name from pi", "error", err)
		return
	}
	if err := s.onName(name); err != nil {
		s.logger.Warn("adopt session name from pi", "error", err)
	}
}

func (s *piSession) scheduleCheckpoint(throughSeq, idleToken uint64) {
	ctx, cancel := context.WithTimeout(context.Background(), s.historyWait)
	request, err := s.beginHistoryRequest(ctx)
	if err != nil {
		cancel()
		s.logger.Error("start session history checkpoint", "error", err)
		s.finishCheckpoint(idleToken)
		return
	}
	s.backgroundWG.Add(1)
	go func() {
		defer s.backgroundWG.Done()
		defer cancel()
		defer s.finishCheckpoint(idleToken)
		response, err := s.awaitHistoryRequest(ctx, request)
		if err != nil {
			s.logger.Error("checkpoint session history", "error", err)
			return
		}

		s.replayMu.Lock()
		if throughSeq >= s.historyThrough {
			err = s.checkpointHistoryLocked(response, throughSeq)
		}
		s.replayMu.Unlock()
		if err != nil {
			s.logger.Error("persist session history checkpoint", "error", err)
			return
		}
	}()
}

// addClient installs ready, the stable get_entries snapshot, and the active
// turn replay as one ordered backlog. Holding replayMu through registration
// establishes the live output high-water mark without a gap.
func (s *piSession) addClient(client *wsClient, ready []byte, includeHistory bool) bool {
	s.replayMu.Lock()
	defer s.replayMu.Unlock()

	initial := make([][]byte, 0, len(s.replay)+4)
	initial = append(initial, ready)
	if includeHistory {
		if !s.historyReady {
			return false
		}
		initial = append(initial, bytes.Clone(s.historyResponse))
		fromSeq := s.historyThrough + 1
		if len(s.replay) > 0 {
			fromSeq = s.replay[0].Seq
		}
		initial = append(initial, mustGatewayEvent(gatewayEvent{
			Type:       "pi2ws",
			Event:      "replay_begin",
			FromSeq:    fromSeq,
			ThroughSeq: s.outputSeq,
		}))
		if s.replayTruncated {
			initial = append(initial, mustGatewayEvent(gatewayEvent{
				Type:    "pi2ws",
				Event:   "replay_unavailable",
				Code:    "replay_buffer_exceeded",
				Message: "active turn output exceeded the replay buffer limit",
			}))
		} else {
			for _, record := range s.replay {
				initial = append(initial, mustGatewayEvent(gatewayEvent{
					Type:    "pi2ws",
					Event:   "replay",
					Seq:     record.Seq,
					Payload: json.RawMessage(record.Payload),
				}))
			}
		}
		initial = append(initial, mustGatewayEvent(gatewayEvent{
			Type:       "pi2ws",
			Event:      "replay_end",
			ThroughSeq: s.outputSeq,
		}))
	}
	client.setInitial(initial)

	s.clientsMu.Lock()
	defer s.clientsMu.Unlock()
	if s.clientsClosed {
		return false
	}
	s.clients[client] = s.outputSeq
	return true
}

func mustGatewayEvent(event gatewayEvent) []byte {
	message, err := json.Marshal(event)
	if err != nil {
		panic(err)
	}
	return message
}

func (s *piSession) noteInput() {
	s.idleMu.Lock()
	defer s.idleMu.Unlock()
	s.idleGeneration++
	if s.settled && !s.checkpointing {
		s.resetIdleTimerLocked(s.idleGeneration)
	}
}

func (s *piSession) markAgentStarted() {
	s.idleMu.Lock()
	defer s.idleMu.Unlock()
	s.idleGeneration++
	s.settled = false
	if s.idleTimer != nil {
		s.idleTimer.Stop()
		s.idleTimer = nil
	}
}

func (s *piSession) markAgentSettled() uint64 {
	s.idleMu.Lock()
	defer s.idleMu.Unlock()
	s.idleGeneration++
	s.settled = true
	s.checkpointing = true
	s.checkpointToken = s.idleGeneration
	if s.idleTimer != nil {
		s.idleTimer.Stop()
		s.idleTimer = nil
	}
	return s.idleGeneration
}

func (s *piSession) startIdleTimer() {
	s.idleMu.Lock()
	defer s.idleMu.Unlock()
	if s.settled && !s.checkpointing && s.idleTimer == nil {
		s.resetIdleTimerLocked(s.idleGeneration)
	}
}

func (s *piSession) finishCheckpoint(token uint64) {
	s.idleMu.Lock()
	defer s.idleMu.Unlock()
	if !s.checkpointing || s.checkpointToken != token {
		return
	}
	s.checkpointing = false
	if s.settled {
		s.resetIdleTimerLocked(s.idleGeneration)
	}
}

func (s *piSession) resetIdleTimerLocked(token uint64) {
	if s.idleTimer != nil {
		s.idleTimer.Stop()
	}
	s.idleTimer = time.AfterFunc(s.idleAfter, func() {
		s.idleMu.Lock()
		if !s.settled || s.idleGeneration != token {
			s.idleMu.Unlock()
			return
		}
		s.idleTimer = nil
		s.idleMu.Unlock()
		s.logger.Info("pi session idle timeout", "idle_for", s.idleAfter)
		s.stop(websocket.CloseNormalClosure, "pi session idle timeout")
	})
}

func (s *piSession) stopIdleTimer() {
	s.idleMu.Lock()
	if s.idleTimer != nil {
		s.idleTimer.Stop()
		s.idleTimer = nil
	}
	s.idleMu.Unlock()
}
