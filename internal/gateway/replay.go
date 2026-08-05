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
	Type    string  `json:"type"`
	ID      string  `json:"id,omitempty"`
	Command string  `json:"command,omitempty"`
	Success bool    `json:"success,omitempty"`
	Name    *string `json:"name,omitempty"`
}

type pendingInternalRequest struct {
	id     string
	waiter chan []byte
}

// ensureHistory initializes the stable JSONL boundary for an existing pi
// session. Only one attach scans the file; concurrent attaches wait for it.
func (s *piSession) ensureHistory(ctx context.Context) error {
	s.historyInitMu.Lock()
	defer s.historyInitMu.Unlock()

	s.replayMu.Lock()
	ready := s.historyReady
	s.replayMu.Unlock()
	if ready {
		return nil
	}

	select {
	case <-ctx.Done():
		return ctx.Err()
	default:
	}
	snapshot, err := s.locateSessionFileSnapshot()
	if err != nil {
		return err
	}
	boundary, err := s.scanHistorySnapshot(snapshot, historyBoundary{})
	if err != nil {
		return err
	}
	s.replayMu.Lock()
	err = s.applyHistoryCheckpointLocked(boundary, s.outputSeq)
	s.replayMu.Unlock()
	return err
}

func (s *piSession) beginInternalRequest(ctx context.Context, fields map[string]any) (pendingInternalRequest, error) {
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
		return pendingInternalRequest{}, fmt.Errorf("encode internal RPC command: %w", err)
	}
	s.inputMu.Lock()
	defer s.inputMu.Unlock()
	select {
	case s.input <- command:
		return pendingInternalRequest{id: id, waiter: waiter}, nil
	case <-ctx.Done():
		cleanup()
		return pendingInternalRequest{}, ctx.Err()
	case <-s.processEnd:
		cleanup()
		return pendingInternalRequest{}, errors.New("pi session stopped while sending an internal command")
	case <-s.done:
		cleanup()
		return pendingInternalRequest{}, errors.New("pi session stopped while sending an internal command")
	}
}

func (s *piSession) awaitInternalRequest(ctx context.Context, request pendingInternalRequest) ([]byte, error) {
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
		return nil, errors.New("pi session stopped while waiting for an internal command")
	case <-s.done:
		return nil, errors.New("pi session stopped while waiting for an internal command")
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
	if validJSON {
		message = s.annotateUserSource(message, envelope.Type)
		if envelope.Type == "response" {
			if envelope.Success {
				s.userSources.confirmResponse(envelope.ID)
			} else {
				s.userSources.reject(envelope.ID)
			}
		}
	}
	if validJSON && s.deliverInternal(message, envelope) {
		return
	}
	if validJSON && envelope.Type == "session_info_changed" && !s.acceptObservedSessionName(envelope.Name) {
		return
	}
	if validJSON && envelope.Type == "response" && envelope.Command == "get_state" && envelope.Success {
		s.adoptObservedSessionName(message)
	}

	s.replayMu.Lock()
	s.outputSeq++
	outputSeq := s.outputSeq
	var replayErr error
	replayMessage := message
	if validJSON && replayableOutput(envelope.Type) {
		var compactErr error
		replayMessage, _, compactErr = compactReplayPayload(message)
		if compactErr != nil {
			replayErr = compactErr
		} else {
			record := replayRecord{Seq: outputSeq, Payload: replayMessage}
			replayErr = s.appendReplayLocked(record)
		}
		if replayErr == nil {
			switch envelope.Type {
			case "message_end", "tool_execution_end":
				if s.syncReaders == 0 {
					replayErr = s.compactReplayLogLocked(s.historyThrough)
				} else {
					s.replayNeedsCompaction = true
					replayErr = s.syncReplayLocked()
				}
			case "agent_settled":
				replayErr = s.syncReplayLocked()
			}
		}
	}
	s.replayMu.Unlock()
	if replayErr != nil {
		s.fail(fmt.Errorf("persist pi output before broadcast: %w", replayErr))
		return
	}

	var checkpointToken uint64
	var checkpointSnapshot sessionFileSnapshot
	var checkpointSnapshotErr error
	switch envelope.Type {
	case "agent_start":
		s.markAgentStarted()
	case "agent_settled":
		checkpointSnapshot, checkpointSnapshotErr = s.locateSessionFileSnapshot()
		checkpointToken = s.markAgentSettled()
		if s.onActivity != nil {
			if err := s.onActivity(); err != nil {
				s.logger.Warn("touch settled session", "error", err)
			}
		}
	}

	if checkpointToken != 0 {
		s.scheduleCheckpoint(outputSeq, checkpointToken, checkpointSnapshot, checkpointSnapshotErr)
	}
	s.broadcast(message, replayMessage, outputSeq, validJSON && replayableOutput(envelope.Type))
}

func (s *piSession) adoptObservedSessionName(message []byte) {
	var response struct {
		Data struct {
			SessionName *string `json:"sessionName"`
		} `json:"data"`
	}
	if err := json.Unmarshal(message, &response); err != nil || response.Data.SessionName == nil {
		return
	}
	s.acceptObservedSessionName(response.Data.SessionName)
}

func (s *piSession) acceptObservedSessionName(observed *string) bool {
	if observed == nil {
		s.logger.Warn("ignore cleared session name from pi")
		return false
	}
	name, err := normalizeSessionName(*observed)
	if err != nil {
		s.logger.Warn("ignore invalid session name from pi", "error", err)
		return false
	}
	if s.onName == nil {
		return true
	}
	accepted, err := s.onName(name)
	if err != nil {
		s.logger.Warn("adopt session name from pi", "error", err)
		return false
	}
	if !accepted {
		s.logger.Info("ignore session name superseded by gateway metadata")
	}
	return accepted
}

func (s *piSession) scheduleCheckpoint(
	throughSeq, idleToken uint64,
	snapshot sessionFileSnapshot,
	snapshotErr error,
) {
	s.backgroundWG.Add(1)
	go func() {
		defer s.backgroundWG.Done()
		defer s.finishCheckpoint(idleToken)
		if snapshotErr != nil {
			s.logger.Error("capture session history checkpoint", "error", snapshotErr)
			return
		}
		s.replayMu.Lock()
		baseline := historyBoundary{
			File: s.historyFile, Offset: s.historyOffset, EntryID: s.historyEntryID,
		}
		s.replayMu.Unlock()
		boundary, err := s.scanHistorySnapshot(snapshot, baseline)
		if err != nil {
			s.logger.Error("scan session history checkpoint", "error", err)
			return
		}

		s.replayMu.Lock()
		if throughSeq >= s.historyThrough {
			err = s.applyHistoryCheckpointLocked(boundary, throughSeq)
		}
		s.replayMu.Unlock()
		if err != nil {
			s.logger.Error("persist session history checkpoint", "error", err)
			return
		}
	}()
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
