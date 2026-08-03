package gateway

import (
	"bufio"
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
)

const (
	historyCacheFileName = "pi2ws-history.json"
	replayLogFileName    = "pi2ws-replay.log"
	replayStoreVersion   = 1
)

type persistedHistory struct {
	Version    int             `json:"version"`
	ThroughSeq uint64          `json:"through_seq"`
	Response   json.RawMessage `json:"response"`
}

type persistedReplayRecord struct {
	Seq     uint64          `json:"seq"`
	Payload json.RawMessage `json:"payload"`
}

func (s *piSession) openReplayStore(newSession bool) error {
	s.replayMu.Lock()
	defer s.replayMu.Unlock()

	if newSession {
		if err := s.writeHistoryCacheLocked(s.historyResponse, 0); err != nil {
			return err
		}
		file, err := os.OpenFile(
			filepath.Join(s.dir, replayLogFileName),
			os.O_CREATE|os.O_TRUNC|os.O_WRONLY|os.O_APPEND,
			0o600,
		)
		if err != nil {
			return fmt.Errorf("create replay log: %w", err)
		}
		s.replayFile = file
		return nil
	}

	cachePath := filepath.Join(s.dir, historyCacheFileName)
	data, err := os.ReadFile(cachePath)
	if err == nil {
		var cache persistedHistory
		if err := json.Unmarshal(data, &cache); err != nil {
			return fmt.Errorf("decode history cache: %w", err)
		}
		if cache.Version != replayStoreVersion || len(cache.Response) == 0 || !json.Valid(cache.Response) {
			return errors.New("invalid history cache")
		}
		normalized, err := normalizeHistoryResponse(cache.Response)
		if err != nil {
			return fmt.Errorf("validate history cache: %w", err)
		}
		s.historyReady = true
		s.historyResponse = normalized
		s.historyThrough = cache.ThroughSeq
		s.outputSeq = cache.ThroughSeq
	} else if !errors.Is(err, os.ErrNotExist) {
		return fmt.Errorf("read history cache: %w", err)
	}

	logPath := filepath.Join(s.dir, replayLogFileName)
	if err := s.loadReplayLogLocked(logPath); err != nil {
		return err
	}
	file, err := os.OpenFile(logPath, os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0o600)
	if err != nil {
		return fmt.Errorf("open replay log: %w", err)
	}
	s.replayFile = file
	return nil
}

func (s *piSession) loadReplayLogLocked(path string) error {
	file, err := os.Open(path)
	if errors.Is(err, os.ErrNotExist) {
		return nil
	}
	if err != nil {
		return fmt.Errorf("open replay log for recovery: %w", err)
	}
	defer file.Close()

	scanner := bufio.NewScanner(file)
	scanner.Buffer(make([]byte, 64<<10), int(s.maxEvent)+(1<<20))
	lastSeq := s.historyThrough
	for scanner.Scan() {
		var record persistedReplayRecord
		if err := json.Unmarshal(scanner.Bytes(), &record); err != nil || record.Seq == 0 || !json.Valid(record.Payload) {
			s.replayTruncated = true
			continue
		}
		if record.Seq <= s.historyThrough {
			continue
		}
		if record.Seq <= lastSeq {
			s.replayTruncated = true
			continue
		}
		lastSeq = record.Seq
		s.outputSeq = record.Seq
		s.replayBytes += int64(len(record.Payload))
		if s.replayBytes > s.maxReplay {
			s.replay = nil
			s.replayBytes = 0
			s.replayTruncated = true
			continue
		}
		s.replay = append(s.replay, replayRecord{Seq: record.Seq, Payload: bytes.Clone(record.Payload)})
	}
	if err := scanner.Err(); err != nil {
		return fmt.Errorf("read replay log: %w", err)
	}
	return nil
}

func (s *piSession) appendReplayLocked(record replayRecord) error {
	if s.replayFile == nil {
		return errors.New("replay log is closed")
	}
	line, err := json.Marshal(persistedReplayRecord{Seq: record.Seq, Payload: json.RawMessage(record.Payload)})
	if err != nil {
		return fmt.Errorf("encode replay record: %w", err)
	}
	line = append(line, '\n')
	if _, err := s.replayFile.Write(line); err != nil {
		return fmt.Errorf("append replay record: %w", err)
	}
	return nil
}

func (s *piSession) syncReplayLocked() error {
	if s.replayFile == nil {
		return errors.New("replay log is closed")
	}
	if err := s.replayFile.Sync(); err != nil {
		return fmt.Errorf("sync replay log: %w", err)
	}
	return nil
}

func (s *piSession) checkpointHistoryLocked(response []byte, throughSeq uint64) error {
	if err := s.writeHistoryCacheLocked(response, throughSeq); err != nil {
		return err
	}

	kept := make([]replayRecord, 0, len(s.replay))
	for _, record := range s.replay {
		if record.Seq > throughSeq {
			kept = append(kept, record)
		}
	}
	// Once the in-memory replay overflows, it no longer contains a complete
	// representation of the WAL. If output from a newer turn arrived while
	// this checkpoint RPC was in flight, preserve the full log; a restart can
	// skip records covered by historyThrough and recover the newer tail.
	preserveReplayLog := s.replayTruncated && s.outputSeq > throughSeq
	if !preserveReplayLog {
		if err := s.rewriteReplayLogLocked(kept); err != nil {
			return err
		}
	}

	s.historyResponse = bytes.Clone(response)
	s.historyReady = true
	s.historyThrough = throughSeq
	s.replay = kept
	s.replayBytes = 0
	for _, record := range kept {
		s.replayBytes += int64(len(record.Payload))
	}
	if !preserveReplayLog && s.outputSeq <= throughSeq {
		s.replayTruncated = false
	}
	return nil
}

func (s *piSession) writeHistoryCacheLocked(response []byte, throughSeq uint64) error {
	cache := persistedHistory{
		Version:    replayStoreVersion,
		ThroughSeq: throughSeq,
		Response:   json.RawMessage(response),
	}
	data, err := json.Marshal(cache)
	if err != nil {
		return fmt.Errorf("encode history cache: %w", err)
	}
	return writeAtomicFile(s.dir, historyCacheFileName, data)
}

func (s *piSession) rewriteReplayLogLocked(records []replayRecord) error {
	path := filepath.Join(s.dir, replayLogFileName)
	temp, err := os.CreateTemp(s.dir, ".pi2ws-replay-*")
	if err != nil {
		return fmt.Errorf("create replay log replacement: %w", err)
	}
	tempPath := temp.Name()
	defer os.Remove(tempPath)
	if err := temp.Chmod(0o600); err != nil {
		_ = temp.Close()
		return fmt.Errorf("chmod replay log replacement: %w", err)
	}

	encoder := json.NewEncoder(temp)
	for _, record := range records {
		if err := encoder.Encode(persistedReplayRecord{Seq: record.Seq, Payload: json.RawMessage(record.Payload)}); err != nil {
			_ = temp.Close()
			return fmt.Errorf("write replay log replacement: %w", err)
		}
	}
	if err := temp.Sync(); err != nil {
		_ = temp.Close()
		return fmt.Errorf("sync replay log replacement: %w", err)
	}
	if err := temp.Close(); err != nil {
		return fmt.Errorf("close replay log replacement: %w", err)
	}

	if s.replayFile != nil {
		if err := s.replayFile.Close(); err != nil {
			return fmt.Errorf("close old replay log: %w", err)
		}
		s.replayFile = nil
	}
	if err := os.Rename(tempPath, path); err != nil {
		file, openErr := os.OpenFile(path, os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0o600)
		s.replayFile = file
		if openErr != nil {
			return errors.Join(fmt.Errorf("replace replay log: %w", err), fmt.Errorf("reopen replay log: %w", openErr))
		}
		return fmt.Errorf("replace replay log: %w", err)
	}
	file, err := os.OpenFile(path, os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0o600)
	if err != nil {
		return fmt.Errorf("reopen compacted replay log: %w", err)
	}
	s.replayFile = file
	return syncDirectory(s.dir)
}

func writeAtomicFile(dir, name string, data []byte) error {
	temp, err := os.CreateTemp(dir, ".pi2ws-history-*")
	if err != nil {
		return fmt.Errorf("create history cache replacement: %w", err)
	}
	tempPath := temp.Name()
	defer os.Remove(tempPath)
	if err := temp.Chmod(0o600); err != nil {
		_ = temp.Close()
		return fmt.Errorf("chmod history cache replacement: %w", err)
	}
	if _, err := temp.Write(data); err != nil {
		_ = temp.Close()
		return fmt.Errorf("write history cache replacement: %w", err)
	}
	if _, err := temp.Write([]byte{'\n'}); err != nil {
		_ = temp.Close()
		return fmt.Errorf("finish history cache replacement: %w", err)
	}
	if err := temp.Sync(); err != nil {
		_ = temp.Close()
		return fmt.Errorf("sync history cache replacement: %w", err)
	}
	if err := temp.Close(); err != nil {
		return fmt.Errorf("close history cache replacement: %w", err)
	}
	if err := os.Rename(tempPath, filepath.Join(dir, name)); err != nil {
		return fmt.Errorf("replace history cache: %w", err)
	}
	return syncDirectory(dir)
}

func syncDirectory(path string) error {
	dir, err := os.Open(path)
	if err != nil {
		return fmt.Errorf("open session directory for sync: %w", err)
	}
	defer dir.Close()
	if err := dir.Sync(); err != nil {
		return fmt.Errorf("sync session directory: %w", err)
	}
	return nil
}

func (s *piSession) closeReplayStore() {
	s.replayMu.Lock()
	if s.replayFile != nil {
		if err := s.replayFile.Close(); err != nil {
			s.logger.Warn("close replay log", "error", err)
		}
		s.replayFile = nil
	}
	s.replayMu.Unlock()
}
