package gateway

import (
	"bufio"
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
)

// syncChunkBytes is deliberately far below the WebSocket/pi JSONL record
// limit. Total history and total active-turn output are unbounded by this
// value; it only bounds one attach frame and its transient allocations.
const syncChunkBytes = 256 << 10

type attachSnapshot struct {
	historyFile    string
	historyOffset  int64
	historyEntryID string
	historyThrough uint64
	outputSeq      uint64
}

type replayResume struct {
	Base    uint64
	Since   uint64
	Present bool
}

func (s *piSession) addLiveClient(client *wsClient, ready []byte) bool {
	s.replayMu.Lock()
	defer s.replayMu.Unlock()
	if !client.sendBlocking(ready) {
		return false
	}
	return s.registerClientLocked(client, s.outputSeq)
}

func (s *piSession) registerClientLocked(client *wsClient, liveAfter uint64) bool {
	select {
	case <-client.done:
		return false
	default:
	}
	s.clientsMu.Lock()
	defer s.clientsMu.Unlock()
	if s.clientsClosed {
		return false
	}
	s.clients[client] = liveAfter
	return true
}

func (s *piSession) beginAttachSync(client *wsClient) (attachSnapshot, error) {
	s.replayMu.Lock()
	defer s.replayMu.Unlock()
	if !s.historyReady || s.replayFile == nil {
		return attachSnapshot{}, errors.New("session history is not ready")
	}
	// Install a muted registration immediately so shutdown/process failure can
	// still find and close a client whose history stream has not caught up yet.
	if !s.registerClientLocked(client, ^uint64(0)) {
		return attachSnapshot{}, errors.New("session stopped before history sync")
	}
	s.syncReaders++
	return attachSnapshot{
		historyFile:    s.historyFile,
		historyOffset:  s.historyOffset,
		historyEntryID: s.historyEntryID,
		historyThrough: s.historyThrough,
		outputSeq:      s.outputSeq,
	}, nil
}

func (s *piSession) finishAttachSync() {
	s.replayMu.Lock()
	if s.syncReaders > 0 {
		s.syncReaders--
	}
	var err error
	if s.syncReaders == 0 && s.replayNeedsCompaction {
		err = s.compactReplayLogLocked(s.historyThrough)
		if err == nil {
			s.replayNeedsCompaction = false
		}
	}
	s.replayMu.Unlock()
	if err != nil {
		s.logger.Error("compact replay log after attach", "error", err)
	}
}

// syncAttach sends a stable entry delta followed by every WAL record after
// that stable boundary. The last catch-up check and live registration are
// atomic with respect to output sequencing, so there is no replay/live gap.
func (s *piSession) syncAttach(
	client *wsClient,
	ready []byte,
	entrySince string,
	resume replayResume,
) error {
	snapshot, err := s.beginAttachSync(client)
	if err != nil {
		return err
	}
	defer s.finishAttachSync()

	reset, historyStart, historyBytes, err := s.findHistoryStart(snapshot, entrySince)
	if err != nil {
		return err
	}
	// An empty entry cursor is still an exact stable-history cursor while a
	// brand-new turn has not produced its first persistent entry. The replay
	// base disambiguates that resumable case from a client asking for a reset.
	if reset && resume.Present && entrySince == "" && snapshot.historyEntryID == "" &&
		resume.Base == snapshot.historyThrough {
		reset = false
		historyStart = snapshot.historyOffset
		historyBytes = 0
	}
	replayAfter := snapshot.historyThrough
	if resume.Present && !reset && entrySince == snapshot.historyEntryID &&
		resume.Base == snapshot.historyThrough && resume.Since <= snapshot.outputSeq {
		replayAfter = resume.Since
	}
	if !client.sendBlocking(mustGatewayEvent(gatewayEvent{
		Type:       "ohpi",
		Event:      "history_begin",
		Reset:      boolPointer(reset),
		EntryID:    snapshot.historyEntryID,
		ThroughSeq: snapshot.historyThrough,
		TotalBytes: uint64(historyBytes),
	})) {
		return errors.New("client disconnected during history sync")
	}
	if err := s.streamHistory(client, snapshot, historyStart); err != nil {
		return err
	}
	if !client.sendBlocking(mustGatewayEvent(gatewayEvent{
		Type:       "ohpi",
		Event:      "history_end",
		EntryID:    snapshot.historyEntryID,
		ThroughSeq: snapshot.historyThrough,
	})) {
		return errors.New("client disconnected after history sync")
	}
	if !client.sendBlocking(mustGatewayEvent(gatewayEvent{
		Type:       "ohpi",
		Event:      "replay_begin",
		FromSeq:    replayAfter + 1,
		ThroughSeq: snapshot.outputSeq,
	})) {
		return errors.New("client disconnected before replay sync")
	}

	replayPath := filepath.Join(s.dir, replayLogFileName)
	replayFile, err := os.Open(replayPath)
	if err != nil {
		return fmt.Errorf("open replay log for attach: %w", err)
	}
	defer replayFile.Close()
	var replayOffset int64
	for {
		s.replayMu.Lock()
		if err := s.syncReplayLocked(); err != nil {
			s.replayMu.Unlock()
			return err
		}
		info, err := s.replayFile.Stat()
		if err != nil {
			s.replayMu.Unlock()
			return fmt.Errorf("inspect replay log during attach: %w", err)
		}
		targetOffset := info.Size()
		s.replayMu.Unlock()

		if replayOffset < targetOffset {
			if err := streamReplayRange(
				client,
				replayFile,
				&replayOffset,
				targetOffset,
				replayAfter,
			); err != nil {
				return err
			}
		}

		s.replayMu.Lock()
		if err := s.syncReplayLocked(); err != nil {
			s.replayMu.Unlock()
			return err
		}
		info, err = s.replayFile.Stat()
		if err != nil {
			s.replayMu.Unlock()
			return fmt.Errorf("inspect replay log at live handoff: %w", err)
		}
		if replayOffset != info.Size() {
			s.replayMu.Unlock()
			continue
		}

		throughSeq := s.outputSeq
		if !client.sendBlocking(mustGatewayEvent(gatewayEvent{
			Type:       "ohpi",
			Event:      "replay_end",
			ThroughSeq: throughSeq,
		})) || !client.sendBlocking(ready) {
			s.replayMu.Unlock()
			return errors.New("client disconnected at live handoff")
		}
		registered := s.registerClientLocked(client, throughSeq)
		s.replayMu.Unlock()
		if !registered {
			return errors.New("session stopped at live handoff")
		}
		return nil
	}
}

func boolPointer(value bool) *bool {
	return &value
}

func (s *piSession) findHistoryStart(
	snapshot attachSnapshot,
	entrySince string,
) (bool, int64, int64, error) {
	if entrySince == "" || snapshot.historyFile == "" || snapshot.historyOffset == 0 {
		bytes, err := s.historyTransferBytes(snapshot, 0)
		return true, 0, bytes, err
	}
	file, err := os.Open(filepath.Join(s.dir, snapshot.historyFile))
	if err != nil {
		return false, 0, 0, fmt.Errorf("open stable history cursor scan: %w", err)
	}
	defer file.Close()
	reader := bufio.NewReader(io.LimitReader(file, snapshot.historyOffset))
	var offset int64
	for offset < snapshot.historyOffset {
		line, _ := reader.ReadBytes('\n')
		offset += int64(len(line))
		if len(line) == 0 || line[len(line)-1] != '\n' {
			return false, 0, 0, errors.New("stable history ends inside a JSONL record")
		}
		var envelope struct {
			Type string `json:"type"`
			ID   string `json:"id"`
		}
		if err := json.Unmarshal(bytes.TrimSpace(line), &envelope); err != nil {
			return false, 0, 0, fmt.Errorf("decode stable history cursor record: %w", err)
		}
		if envelope.Type != "session" && envelope.ID == entrySince {
			return false, offset, snapshot.historyOffset - offset, nil
		}
	}
	transferBytes, err := s.historyTransferBytes(snapshot, 0)
	return true, 0, transferBytes, err
}

// historyTransferBytes returns the exact binary bytes sent after history_begin.
// A pi session file starts with one metadata record, which is not part of the
// client entry cache.
func (s *piSession) historyTransferBytes(snapshot attachSnapshot, start int64) (int64, error) {
	if snapshot.historyFile == "" || snapshot.historyOffset == 0 || start >= snapshot.historyOffset {
		return 0, nil
	}
	transferBytes := snapshot.historyOffset - start
	if start != 0 {
		return transferBytes, nil
	}
	file, err := os.Open(filepath.Join(s.dir, snapshot.historyFile))
	if err != nil {
		return 0, fmt.Errorf("open stable history size scan: %w", err)
	}
	defer file.Close()
	line, err := bufio.NewReader(io.LimitReader(file, snapshot.historyOffset)).ReadBytes('\n')
	if err != nil && !errors.Is(err, io.EOF) {
		return 0, fmt.Errorf("read stable history metadata: %w", err)
	}
	if len(line) == 0 || line[len(line)-1] != '\n' {
		return 0, errors.New("stable history metadata is incomplete")
	}
	var envelope struct {
		Type string `json:"type"`
	}
	if err := json.Unmarshal(bytes.TrimSpace(line), &envelope); err != nil {
		return 0, fmt.Errorf("decode stable history metadata: %w", err)
	}
	if envelope.Type == "session" {
		transferBytes -= int64(len(line))
	}
	return transferBytes, nil
}

func (s *piSession) streamHistory(client *wsClient, snapshot attachSnapshot, start int64) error {
	if snapshot.historyFile == "" || snapshot.historyOffset == 0 || start >= snapshot.historyOffset {
		return nil
	}
	file, err := os.Open(filepath.Join(s.dir, snapshot.historyFile))
	if err != nil {
		return fmt.Errorf("open stable history stream: %w", err)
	}
	defer file.Close()
	if _, err := file.Seek(start, io.SeekStart); err != nil {
		return fmt.Errorf("seek stable history stream: %w", err)
	}

	reader := bufio.NewReader(io.LimitReader(file, snapshot.historyOffset-start))
	chunk := make([]byte, 0, syncChunkBytes)
	flush := func() error {
		if len(chunk) == 0 {
			return nil
		}
		if !client.sendBinaryBlocking(chunk) {
			return errors.New("client disconnected during history binary stream")
		}
		chunk = chunk[:0]
		return nil
	}
	appendBytes := func(data []byte) error {
		for len(data) > 0 {
			space := syncChunkBytes - len(chunk)
			if space == 0 {
				if err := flush(); err != nil {
					return err
				}
				space = syncChunkBytes
			}
			count := min(space, len(data))
			chunk = append(chunk, data[:count]...)
			data = data[count:]
		}
		return nil
	}

	for {
		line, readErr := reader.ReadBytes('\n')
		if len(line) > 0 {
			if line[len(line)-1] != '\n' {
				return errors.New("stable history stream ends inside a JSONL record")
			}
			var envelope struct {
				Type string `json:"type"`
				ID   string `json:"id"`
			}
			if err := json.Unmarshal(bytes.TrimSpace(line), &envelope); err != nil {
				return fmt.Errorf("decode stable history record: %w", err)
			}
			if envelope.Type != "session" {
				if envelope.ID == "" {
					return errors.New("stable history entry has no id")
				}
				if err := appendBytes(line); err != nil {
					return err
				}
			}
		}
		if readErr != nil {
			if !errors.Is(readErr, io.EOF) {
				return fmt.Errorf("read stable history stream: %w", readErr)
			}
			break
		}
	}
	return flush()
}

func streamReplayRange(
	client *wsClient,
	file *os.File,
	offset *int64,
	throughOffset int64,
	afterSeq uint64,
) error {
	if _, err := file.Seek(*offset, io.SeekStart); err != nil {
		return fmt.Errorf("seek replay stream: %w", err)
	}
	reader := bufio.NewReader(io.LimitReader(file, throughOffset-*offset))
	for *offset < throughOffset {
		line, _ := reader.ReadBytes('\n')
		*offset += int64(len(line))
		if len(line) == 0 || line[len(line)-1] != '\n' {
			return errors.New("replay snapshot ends inside a WAL record")
		}
		var record persistedReplayRecord
		if err := json.Unmarshal(bytes.TrimSpace(line), &record); err != nil || record.Seq == 0 || !json.Valid(record.Payload) {
			return fmt.Errorf("decode replay WAL record ending at byte %d", *offset)
		}
		if record.Seq > afterSeq {
			if err := streamReplayPayload(client, record.Seq, record.Payload); err != nil {
				return err
			}
		}
	}
	return nil
}

func streamReplayPayload(client *wsClient, seq uint64, payload []byte) error {
	if len(payload) == 0 {
		return errors.New("cannot stream an empty replay payload")
	}
	if len(payload) <= syncChunkBytes {
		if !client.sendBlocking(mustGatewayEvent(gatewayEvent{
			Type:       "ohpi",
			Event:      "replay_event",
			Seq:        seq,
			Payload:    payload,
			TotalBytes: uint64(len(payload)),
		})) {
			return errors.New("client disconnected during replay event")
		}
		return nil
	}
	if !client.sendBlocking(mustGatewayEvent(gatewayEvent{
		Type:       "ohpi",
		Event:      "replay_binary_begin",
		Seq:        seq,
		TotalBytes: uint64(len(payload)),
	})) {
		return errors.New("client disconnected before replay binary payload")
	}
	for offset := 0; offset < len(payload); {
		end := min(offset+syncChunkBytes, len(payload))
		if !client.sendBinaryBlocking(payload[offset:end]) {
			return errors.New("client disconnected during replay binary payload")
		}
		offset = end
	}
	return nil
}
