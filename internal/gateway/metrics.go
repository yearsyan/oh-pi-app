package gateway

import (
	"bufio"
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"io/fs"
	"os"
	"path/filepath"
	"time"
)

const (
	sessionMetricsFileName = "ohpi-metrics.jsonl"
	sessionMetricVersion   = 1
)

// sessionMetricSample records one completed, measurable assistant model call.
// Durations are observed by the gateway from pi's live RPC stream.
type sessionMetricSample struct {
	Version          int    `json:"v"`
	TurnIndex        int64  `json:"turn_index"`
	Provider         string `json:"provider,omitempty"`
	Model            string `json:"model,omitempty"`
	StartedAt        int64  `json:"started_at"`
	RecordedAt       int64  `json:"recorded_at"`
	TTFTMillis       int64  `json:"ttft_ms"`
	GenerationMillis int64  `json:"generation_ms"`
	OutputTokens     int64  `json:"output_tokens"`
	StopReason       string `json:"stop_reason,omitempty"`
}

type sessionMetricsResponse struct {
	SessionID             string   `json:"session_id"`
	SampleCount           int64    `json:"sample_count"`
	TotalOutputTokens     int64    `json:"total_output_tokens"`
	TotalGenerationMillis int64    `json:"total_generation_ms"`
	AverageTPS            *float64 `json:"average_tps"`
	AverageTTFTMillis     *float64 `json:"average_ttft_ms"`
}

type sessionMetricTracker struct {
	active     bool
	turnIndex  int64
	startedAt  time.Time
	firstToken time.Time
}

func (t *sessionMetricTracker) observe(payload []byte, receivedAt time.Time) *sessionMetricSample {
	var event struct {
		Type                  string `json:"type"`
		TurnIndex             int64  `json:"turnIndex"`
		AssistantMessageEvent *struct {
			Type  string `json:"type"`
			Delta string `json:"delta"`
		} `json:"assistantMessageEvent"`
		Message *struct {
			Role       string `json:"role"`
			Provider   string `json:"provider"`
			Model      string `json:"model"`
			StopReason string `json:"stopReason"`
			Usage      struct {
				Output int64 `json:"output"`
			} `json:"usage"`
		} `json:"message"`
	}
	if err := json.Unmarshal(payload, &event); err != nil {
		return nil
	}

	switch event.Type {
	case "turn_start":
		t.active = true
		t.turnIndex = event.TurnIndex
		t.startedAt = receivedAt
		t.firstToken = time.Time{}
		return nil
	case "message_update":
		if t.active && t.firstToken.IsZero() && isFirstOutputEvent(event.AssistantMessageEvent) {
			t.firstToken = receivedAt
		}
		return nil
	case "agent_settled":
		t.reset()
		return nil
	case "message_end":
		if event.Message == nil || event.Message.Role != "assistant" {
			return nil
		}
	default:
		return nil
	}

	active := t.active
	turnIndex := t.turnIndex
	startedAt := t.startedAt
	firstToken := t.firstToken
	t.reset()
	if !active || startedAt.IsZero() || firstToken.IsZero() ||
		event.Message.Usage.Output <= 0 ||
		event.Message.StopReason == "error" || event.Message.StopReason == "aborted" {
		return nil
	}

	ttft := firstToken.Sub(startedAt)
	if ttft < 0 {
		ttft = 0
	}
	generation := receivedAt.Sub(firstToken)
	if generation < time.Millisecond {
		generation = time.Millisecond
	}
	return &sessionMetricSample{
		Version:          sessionMetricVersion,
		TurnIndex:        turnIndex,
		Provider:         event.Message.Provider,
		Model:            event.Message.Model,
		StartedAt:        startedAt.UnixMilli(),
		RecordedAt:       receivedAt.UnixMilli(),
		TTFTMillis:       ttft.Milliseconds(),
		GenerationMillis: generation.Milliseconds(),
		OutputTokens:     event.Message.Usage.Output,
		StopReason:       event.Message.StopReason,
	}
}

func (t *sessionMetricTracker) reset() {
	t.active = false
	t.turnIndex = 0
	t.startedAt = time.Time{}
	t.firstToken = time.Time{}
}

func isFirstOutputEvent(event *struct {
	Type  string `json:"type"`
	Delta string `json:"delta"`
}) bool {
	if event == nil {
		return false
	}
	switch event.Type {
	case "text_start", "thinking_start", "toolcall_start":
		return true
	case "text_delta", "thinking_delta", "toolcall_delta":
		return event.Delta != ""
	default:
		return false
	}
}

func (s *sessionStore) appendMetric(id string, sample sessionMetricSample) error {
	if sample.Version != sessionMetricVersion || sample.OutputTokens <= 0 || sample.GenerationMillis <= 0 ||
		sample.TTFTMillis < 0 {
		return errors.New("invalid session metric sample")
	}
	encoded, err := json.Marshal(sample)
	if err != nil {
		return fmt.Errorf("encode session metric: %w", err)
	}
	encoded = append(encoded, '\n')

	s.mu.Lock()
	defer s.mu.Unlock()
	_, dir, err := s.loadLocked(id)
	if err != nil {
		return err
	}
	path := filepath.Join(dir, sessionMetricsFileName)
	if info, statErr := os.Lstat(path); statErr == nil {
		if !info.Mode().IsRegular() {
			return errors.New("session metrics path is not a regular file")
		}
	} else if !errors.Is(statErr, fs.ErrNotExist) {
		return fmt.Errorf("inspect session metrics: %w", statErr)
	}
	file, err := os.OpenFile(path, os.O_RDWR|os.O_CREATE|os.O_APPEND, 0o600)
	if err != nil {
		return fmt.Errorf("open session metrics: %w", err)
	}
	if err := file.Chmod(0o600); err != nil {
		_ = file.Close()
		return fmt.Errorf("secure session metrics: %w", err)
	}
	if err := repairMetricTail(file); err != nil {
		_ = file.Close()
		return fmt.Errorf("repair session metrics: %w", err)
	}
	if _, err := file.Write(encoded); err != nil {
		_ = file.Close()
		return fmt.Errorf("append session metric: %w", err)
	}
	if err := file.Sync(); err != nil {
		_ = file.Close()
		return fmt.Errorf("sync session metrics: %w", err)
	}
	if err := file.Close(); err != nil {
		return fmt.Errorf("close session metrics: %w", err)
	}
	return nil
}

// repairMetricTail drops an interrupted final append before writing the next
// record. A complete JSONL record always ends with LF.
func repairMetricTail(file *os.File) error {
	info, err := file.Stat()
	if err != nil {
		return err
	}
	size := info.Size()
	if size == 0 {
		return nil
	}
	var last [1]byte
	if _, err := file.ReadAt(last[:], size-1); err != nil {
		return err
	}
	if last[0] == '\n' {
		return nil
	}

	const chunkSize int64 = 4096
	buffer := make([]byte, chunkSize)
	for end := size; end > 0; {
		start := end - chunkSize
		if start < 0 {
			start = 0
		}
		n, readErr := file.ReadAt(buffer[:end-start], start)
		if readErr != nil && !errors.Is(readErr, io.EOF) {
			return readErr
		}
		if index := bytes.LastIndexByte(buffer[:n], '\n'); index >= 0 {
			return file.Truncate(start + int64(index) + 1)
		}
		end = start
	}
	return file.Truncate(0)
}

func (s *sessionStore) metrics(id string) (sessionMetricsResponse, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	_, dir, err := s.loadLocked(id)
	if err != nil {
		return sessionMetricsResponse{}, err
	}
	response := sessionMetricsResponse{SessionID: id}
	path := filepath.Join(dir, sessionMetricsFileName)
	info, err := os.Lstat(path)
	if errors.Is(err, fs.ErrNotExist) {
		return response, nil
	}
	if err != nil {
		return sessionMetricsResponse{}, fmt.Errorf("inspect session metrics: %w", err)
	}
	if !info.Mode().IsRegular() {
		return sessionMetricsResponse{}, errors.New("session metrics path is not a regular file")
	}
	file, err := os.Open(path)
	if err != nil {
		return sessionMetricsResponse{}, fmt.Errorf("open session metrics: %w", err)
	}
	defer file.Close()

	reader := bufio.NewReader(file)
	var totalTTFTMillis int64
	for {
		line, readErr := reader.ReadBytes('\n')
		if errors.Is(readErr, io.EOF) {
			// A crash can leave one partial final append. It was never durable as a
			// complete JSONL record, so ignore it.
			break
		}
		if readErr != nil {
			return sessionMetricsResponse{}, fmt.Errorf("read session metrics: %w", readErr)
		}
		line = bytes.TrimSpace(line)
		if len(line) == 0 {
			continue
		}
		var sample sessionMetricSample
		if err := json.Unmarshal(line, &sample); err != nil {
			return sessionMetricsResponse{}, fmt.Errorf("decode session metric: %w", err)
		}
		if sample.Version != sessionMetricVersion || sample.OutputTokens <= 0 ||
			sample.GenerationMillis <= 0 || sample.TTFTMillis < 0 {
			return sessionMetricsResponse{}, errors.New("invalid session metric record")
		}
		response.SampleCount++
		response.TotalOutputTokens += sample.OutputTokens
		response.TotalGenerationMillis += sample.GenerationMillis
		totalTTFTMillis += sample.TTFTMillis
	}

	if response.SampleCount > 0 {
		averageTPS := float64(response.TotalOutputTokens) * 1000 / float64(response.TotalGenerationMillis)
		response.AverageTPS = &averageTPS
		averageTTFT := float64(totalTTFTMillis) / float64(response.SampleCount)
		response.AverageTTFTMillis = &averageTTFT
	}
	return response, nil
}
