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
	"strings"
)

const (
	historyCacheFileName = "pi2ws-history.json"
	replayLogFileName    = "pi2ws-replay.log"
	replayStoreVersion   = 2
)

type persistedHistory struct {
	Version    int    `json:"version"`
	ThroughSeq uint64 `json:"through_seq"`
	File       string `json:"file,omitempty"`
	Offset     int64  `json:"offset,omitempty"`
	EntryID    string `json:"entry_id,omitempty"`
}

type persistedReplayRecord struct {
	Seq     uint64          `json:"seq"`
	Payload json.RawMessage `json:"payload"`
}

type sessionFileSnapshot struct {
	File string
	Size int64
}

type historyBoundary struct {
	File    string
	Offset  int64
	EntryID string
}

func (s *piSession) openReplayStore(newSession bool) error {
	s.replayMu.Lock()
	defer s.replayMu.Unlock()

	if newSession {
		if err := s.writeHistoryCacheLocked(0, historyBoundary{}); err != nil {
			return err
		}
	} else {
		cachePath := filepath.Join(s.dir, historyCacheFileName)
		data, err := os.ReadFile(cachePath)
		if err == nil {
			var cache persistedHistory
			if err := json.Unmarshal(data, &cache); err != nil {
				return fmt.Errorf("decode history cache: %w", err)
			}
			if cache.Version != replayStoreVersion || cache.Offset < 0 ||
				(cache.File != "" && filepath.Base(cache.File) != cache.File) {
				return errors.New("invalid history cache")
			}
			s.historyReady = true
			s.historyFile = cache.File
			s.historyOffset = cache.Offset
			s.historyEntryID = cache.EntryID
			s.historyThrough = cache.ThroughSeq
			s.outputSeq = cache.ThroughSeq
		} else if !errors.Is(err, os.ErrNotExist) {
			return fmt.Errorf("read history cache: %w", err)
		}
	}

	logPath := filepath.Join(s.dir, replayLogFileName)
	if err := s.loadReplayLogLocked(logPath); err != nil {
		return err
	}
	flags := os.O_CREATE | os.O_WRONLY | os.O_APPEND
	if newSession {
		flags |= os.O_TRUNC
	}
	file, err := os.OpenFile(logPath, flags, 0o600)
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

	reader := bufio.NewReader(file)
	var offset int64
	var lastCompleteOffset int64
	var lastSeq uint64
	for {
		line, readErr := reader.ReadBytes('\n')
		offset += int64(len(line))
		if readErr != nil && !errors.Is(readErr, io.EOF) {
			_ = file.Close()
			return fmt.Errorf("read replay log: %w", readErr)
		}
		if len(line) > 0 && line[len(line)-1] == '\n' {
			line = bytes.TrimSuffix(line, []byte{'\n'})
			line = bytes.TrimSuffix(line, []byte{'\r'})
			var record persistedReplayRecord
			if err := json.Unmarshal(line, &record); err != nil || record.Seq == 0 || !json.Valid(record.Payload) {
				_ = file.Close()
				return fmt.Errorf("decode replay record ending at byte %d", offset)
			}
			if record.Seq <= lastSeq {
				_ = file.Close()
				return fmt.Errorf("replay sequence %d is not greater than %d", record.Seq, lastSeq)
			}
			lastSeq = record.Seq
			if record.Seq > s.outputSeq {
				s.outputSeq = record.Seq
			}
			lastCompleteOffset = offset
		} else if len(line) > 0 {
			// A process crash may leave one incomplete final append. It was never
			// durable enough to replay, so discard only that partial record.
			break
		}
		if errors.Is(readErr, io.EOF) {
			break
		}
	}
	if err := file.Close(); err != nil {
		return fmt.Errorf("close replay log after recovery: %w", err)
	}
	if offset != lastCompleteOffset {
		if err := os.Truncate(path, lastCompleteOffset); err != nil {
			return fmt.Errorf("discard partial replay record: %w", err)
		}
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

func (s *piSession) locateSessionFileSnapshot() (sessionFileSnapshot, error) {
	entries, err := os.ReadDir(s.dir)
	if err != nil {
		return sessionFileSnapshot{}, fmt.Errorf("list pi session files: %w", err)
	}
	wantedSuffix := "_" + s.id + ".jsonl"
	var selected os.DirEntry
	var selectedModTime int64
	for _, entry := range entries {
		if entry.IsDir() || !strings.HasSuffix(entry.Name(), ".jsonl") {
			continue
		}
		info, err := entry.Info()
		if err != nil {
			return sessionFileSnapshot{}, fmt.Errorf("inspect pi session file %q: %w", entry.Name(), err)
		}
		preferred := strings.HasSuffix(entry.Name(), wantedSuffix)
		selectedPreferred := selected != nil && strings.HasSuffix(selected.Name(), wantedSuffix)
		modified := info.ModTime().UnixNano()
		if selected == nil || (preferred && !selectedPreferred) ||
			(preferred == selectedPreferred && modified > selectedModTime) {
			selected = entry
			selectedModTime = modified
		}
	}
	if selected == nil {
		return sessionFileSnapshot{}, errors.New("pi session JSONL file does not exist")
	}
	info, err := selected.Info()
	if err != nil {
		return sessionFileSnapshot{}, fmt.Errorf("inspect pi session file %q: %w", selected.Name(), err)
	}
	return sessionFileSnapshot{File: selected.Name(), Size: info.Size()}, nil
}

func (s *piSession) scanHistorySnapshot(
	snapshot sessionFileSnapshot,
	baseline historyBoundary,
) (historyBoundary, error) {
	if snapshot.File == "" || filepath.Base(snapshot.File) != snapshot.File || snapshot.Size < 0 {
		return historyBoundary{}, errors.New("invalid pi session file snapshot")
	}
	file, err := os.Open(filepath.Join(s.dir, snapshot.File))
	if err != nil {
		return historyBoundary{}, fmt.Errorf("open pi session history: %w", err)
	}
	defer file.Close()

	boundary := historyBoundary{File: snapshot.File}
	var offset int64
	if baseline.File == snapshot.File && baseline.Offset >= 0 && baseline.Offset <= snapshot.Size {
		boundary = baseline
		offset = baseline.Offset
		if _, err := file.Seek(offset, io.SeekStart); err != nil {
			return historyBoundary{}, fmt.Errorf("seek pi session history checkpoint: %w", err)
		}
	}
	reader := bufio.NewReader(io.LimitReader(file, snapshot.Size-offset))
	for {
		line, readErr := reader.ReadBytes('\n')
		offset += int64(len(line))
		if readErr != nil && !errors.Is(readErr, io.EOF) {
			return historyBoundary{}, fmt.Errorf("read pi session history: %w", readErr)
		}
		if len(line) > 0 && line[len(line)-1] == '\n' {
			line = bytes.TrimSuffix(line, []byte{'\n'})
			line = bytes.TrimSuffix(line, []byte{'\r'})
			var envelope struct {
				Type string `json:"type"`
				ID   string `json:"id"`
			}
			if err := json.Unmarshal(line, &envelope); err != nil {
				return historyBoundary{}, fmt.Errorf("decode pi session entry ending at byte %d: %w", offset, err)
			}
			if envelope.Type != "session" {
				if envelope.ID == "" {
					return historyBoundary{}, fmt.Errorf("pi session entry ending at byte %d has no id", offset)
				}
				boundary.EntryID = envelope.ID
			}
			boundary.Offset = offset
		} else if len(line) > 0 {
			// The captured size should end on a JSONL boundary. Ignore a partial
			// tail defensively rather than treating it as stable history.
			break
		}
		if errors.Is(readErr, io.EOF) {
			break
		}
	}
	return boundary, nil
}

func (s *piSession) applyHistoryCheckpointLocked(boundary historyBoundary, throughSeq uint64) error {
	if throughSeq < s.historyThrough {
		return nil
	}
	if err := s.writeHistoryCacheLocked(throughSeq, boundary); err != nil {
		return err
	}
	s.historyReady = true
	s.historyFile = boundary.File
	s.historyOffset = boundary.Offset
	s.historyEntryID = boundary.EntryID
	s.historyThrough = throughSeq
	if s.syncReaders == 0 {
		s.replayNeedsCompaction = true
		if err := s.compactReplayLogLocked(throughSeq); err != nil {
			return err
		}
		s.replayNeedsCompaction = false
	} else {
		s.replayNeedsCompaction = true
	}
	return nil
}

func (s *piSession) writeHistoryCacheLocked(throughSeq uint64, boundary historyBoundary) error {
	cache := persistedHistory{
		Version:    replayStoreVersion,
		ThroughSeq: throughSeq,
		File:       boundary.File,
		Offset:     boundary.Offset,
		EntryID:    boundary.EntryID,
	}
	data, err := json.Marshal(cache)
	if err != nil {
		return fmt.Errorf("encode history cache: %w", err)
	}
	return writeAtomicFile(s.dir, historyCacheFileName, data)
}

func (s *piSession) compactReplayLogLocked(throughSeq uint64) error {
	path := filepath.Join(s.dir, replayLogFileName)
	input, err := os.Open(path)
	if errors.Is(err, os.ErrNotExist) {
		return nil
	}
	if err != nil {
		return fmt.Errorf("open replay log for compaction: %w", err)
	}

	temp, err := os.CreateTemp(s.dir, ".pi2ws-replay-*")
	if err != nil {
		_ = input.Close()
		return fmt.Errorf("create replay log replacement: %w", err)
	}
	tempPath := temp.Name()
	defer os.Remove(tempPath)
	if err := temp.Chmod(0o600); err != nil {
		_ = input.Close()
		_ = temp.Close()
		return fmt.Errorf("chmod replay log replacement: %w", err)
	}

	reader := bufio.NewReader(input)
	for {
		line, readErr := reader.ReadBytes('\n')
		if len(line) > 0 {
			if line[len(line)-1] != '\n' {
				_ = input.Close()
				_ = temp.Close()
				return errors.New("replay log ends with an incomplete record")
			}
			trimmed := bytes.TrimSuffix(line, []byte{'\n'})
			trimmed = bytes.TrimSuffix(trimmed, []byte{'\r'})
			var record persistedReplayRecord
			if err := json.Unmarshal(trimmed, &record); err != nil || record.Seq == 0 || !json.Valid(record.Payload) {
				_ = input.Close()
				_ = temp.Close()
				return errors.New("replay log contains an invalid record")
			}
			if record.Seq > throughSeq {
				if _, err := temp.Write(line); err != nil {
					_ = input.Close()
					_ = temp.Close()
					return fmt.Errorf("write replay log replacement: %w", err)
				}
			}
		}
		if readErr != nil {
			if !errors.Is(readErr, io.EOF) {
				_ = input.Close()
				_ = temp.Close()
				return fmt.Errorf("read replay log for compaction: %w", readErr)
			}
			break
		}
	}
	if err := input.Close(); err != nil {
		_ = temp.Close()
		return fmt.Errorf("close replay log after compaction: %w", err)
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
